package io.embodit.puzzlebox.domain

/** One placement the solver proposes: a block type in a normalised orientation landing on [cells]. */
data class Placement(val type: PieceType, val rot: Int, val cells: List<Cell>) {
    /** The mouth cell to tap so the drop rule lands it exactly here (its anchor column). */
    val anchor: Pair<Int, Int> get() = anchorOf(cells).let { (ax, az) -> ax to az }
}

/**
 * The run-time solver (design gameSpec.solver): state = filled-cell bitmask (<= 27 bits) + the blocks left; a placement
 * is droppable when every cell is supported (box floor, a filled cell or the same block below) and nothing filled is
 * above it in its column, so the drop rule lands it exactly there and it seals no hole. DFS with a memo of dead states.
 * Pure and thread-safe (one instance per box size, built lazily).
 */
class Solver(val dims: Dims) {
    private class Cand(val mask: Long, val support: Long, val above: Long, val rot: Int, val cells: List<Cell>)

    private val cands = HashMap<PieceType, List<Cand>>()

    private fun candidates(t: PieceType): List<Cand> = synchronized(cands) {
        cands.getOrPut(t) {
            val out = ArrayList<Cand>()
            for ((rot, shape) in Orient.distinct(t)) {
                val s = Orient.size(shape)
                if (s.x > dims.x || s.y > dims.y || s.z > dims.z) continue
                for (oy in 0..dims.y - s.y) for (oz in 0..dims.z - s.z) for (ox in 0..dims.x - s.x) {
                    val cells = shape.map { Cell(it.x + ox, it.y + oy, it.z + oz) }
                    var mask = 0L
                    for (c in cells) mask = mask or (1L shl dims.index(c))
                    var support = 0L; var above = 0L
                    for (c in cells) {
                        if (c.y > 0) { val b = 1L shl dims.index(Cell(c.x, c.y - 1, c.z)); if (b and mask == 0L) support = support or b }
                        for (y in c.y + 1 until dims.y) { val a = 1L shl dims.index(Cell(c.x, y, c.z)); if (a and mask == 0L) above = above or a }
                    }
                    out += Cand(mask, support, above, rot, cells)
                }
            }
            out
        }
    }

    class Timeout : RuntimeException("solver timeout")

    /**
     * A full sequence of placements from [filled] with the multiset [left] (types of the blocks not in the box), or null
     * when the box can't be filled from here. Throws [Timeout] past [deadlineNs] (System.nanoTime based).
     */
    fun solve(filled: Long, left: List<PieceType>, deadlineNs: Long = Long.MAX_VALUE): List<Placement>? {
        val full = (1L shl dims.volume) - 1
        if (java.lang.Long.bitCount(filled) + left.sumOf { it.cells.size } != dims.volume) return null
        val types = left.distinct().sortedBy { it.ordinal }
        val counts = IntArray(types.size) { i -> left.count { it == types[i] } }
        val dead = HashSet<String>()
        val path = ArrayList<Placement>()
        var steps = 0
        fun key(m: Long) = m.toString() + counts.joinToString(",", "|")
        fun dfs(m: Long): Boolean {
            if (m == full) return true
            if (++steps and 1023 == 0 && System.nanoTime() > deadlineNs) throw Timeout()
            val k = key(m)
            if (k in dead) return false
            // the lowest empty cell (y, z, x order) must be filled eventually; a quick prune: some candidate of some
            // remaining type has to be able to cover it at some point - checked implicitly by the full search below
            for (i in types.indices) {
                if (counts[i] == 0) continue
                for (c in candidates(types[i])) {
                    if (c.mask and m != 0L || c.support and m != c.support || c.above and m != 0L) continue
                    counts[i]--
                    path += Placement(types[i], c.rot, c.cells)
                    if (dfs(m or c.mask)) { counts[i]++; return true }
                    path.removeAt(path.size - 1)
                    counts[i]++
                }
            }
            dead += k
            return false
        }
        return if (dfs(filled)) ArrayList(path) else null
    }

    /** Only the next step of [solve] (hint / help). */
    fun next(filled: Long, left: List<PieceType>, deadlineNs: Long = Long.MAX_VALUE): Placement? = solve(filled, left, deadlineNs)?.firstOrNull()

    companion object {
        private val cache = HashMap<Dims, Solver>()
        fun of(d: Dims): Solver = synchronized(cache) { cache.getOrPut(d) { Solver(d) } }
    }
}
