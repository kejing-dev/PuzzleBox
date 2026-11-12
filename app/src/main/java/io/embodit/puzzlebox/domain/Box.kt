package io.embodit.puzzlebox.domain

/** What happens when a block is let go over a mouth cell (design gameSpec.dropRule / reject). */
sealed interface DropResult {
    /** Lands on [cells] (box coordinates); [fall] = how many cells it fell below the mouth (deeper = lower "嗒"). */
    data class Lands(val cells: List<Cell>, val anchor: Pair<Int, Int>, val fall: Int) : DropResult
    /** The footprint is larger than the mouth in this orientation ("这个方向放不进，转一下"). */
    data object TooBig : DropResult
    /** Where it stops, some cell is above the mouth ("冒出盒口了"); [cells] is where it would stop (red ghost). */
    data class SticksOut(val cells: List<Cell>, val anchor: Pair<Int, Int>) : DropResult
}

/**
 * The cells of one box: owner[i] = index of the block filling cell i, or -1. Pure; every rule of the game lives here
 * (drop, holes, column depth, what can be taken out, full).
 */
class BoxGrid(val dims: Dims, val owner: IntArray = IntArray(dims.volume) { -1 }) {
    fun copy() = BoxGrid(dims, owner.copyOf())
    fun at(c: Cell): Int = if (dims.contains(c)) owner[dims.index(c)] else -1
    fun filled(c: Cell): Boolean = at(c) >= 0
    val full: Boolean get() = owner.all { it >= 0 }
    val filledCount: Int get() = owner.count { it >= 0 }

    fun mask(): Long { var m = 0L; for (i in owner.indices) if (owner[i] >= 0) m = m or (1L shl i); return m }

    fun put(piece: Int, cells: List<Cell>) { for (c in cells) { require(dims.contains(c) && owner[dims.index(c)] < 0) { "cell $c taken" }; owner[dims.index(c)] = piece } }
    fun remove(piece: Int) { for (i in owner.indices) if (owner[i] == piece) owner[i] = -1 }
    fun cellsOf(piece: Int): List<Cell> = owner.indices.filter { owner[it] == piece }.map { dims.cellAt(it) }

    /**
     * The drop rule: [shape] (normalised cells in the block's current orientation) aimed at mouth cell ([cx], [cz]).
     * The anchor column goes over (cx, cz); a footprint sticking past the mouth slides back inside (lenient clamp); the
     * block then falls along the box's own down and stops at the first contact.
     */
    fun drop(shape: List<Cell>, cx: Int, cz: Int): DropResult {
        val size = Orient.size(shape)
        if (size.x > dims.x || size.z > dims.z) return DropResult.TooBig
        val (ax, az) = anchorOf(shape)
        val ox = (cx - ax).coerceIn(0, dims.x - size.x)
        val oz = (cz - az).coerceIn(0, dims.z - size.z)
        // start with the block's bottom at the mouth and fall while the next step is free
        var oy = dims.y
        fun fits(y: Int) = shape.all { c -> val p = Cell(c.x + ox, c.y + y, c.z + oz); p.y >= dims.y || !filled(p) }
        while (oy > 0 && fits(oy - 1)) oy--
        val cells = shape.map { Cell(it.x + ox, it.y + oy, it.z + oz) }
        val anchor = (ax + ox) to (az + oz)
        if (cells.any { it.y >= dims.y }) return DropResult.SticksOut(cells, anchor)
        return DropResult.Lands(cells, anchor, dims.y - oy - size.y)
    }

    /** Sealed holes (design gameSpec.sealedHole): empty cells with a filled cell somewhere above in the same column. */
    fun holes(): List<Cell> {
        val out = ArrayList<Cell>()
        for (x in 0 until dims.x) for (z in 0 until dims.z) {
            var covered = false
            for (y in dims.y - 1 downTo 0) {
                val c = Cell(x, y, z)
                if (filled(c)) covered = true else if (covered) out += c
            }
        }
        return out
    }

    /** "还能放几格" (design gameSpec.columnDots): empty cells from the mouth down to the first filled cell. */
    fun depth(x: Int, z: Int): Int {
        var d = 0
        for (y in dims.y - 1 downTo 0) { if (filled(Cell(x, y, z))) break; d++ }
        return d
    }

    /** A block can come out when nothing of another block is above any of its cells (design gameSpec.takeOut). */
    fun canTakeOut(piece: Int): Boolean = cellsOf(piece).all { c -> (c.y + 1 until dims.y).all { y -> at(Cell(c.x, y, c.z)).let { it < 0 || it == piece } } }
}
