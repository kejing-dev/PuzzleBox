package com.kejing.puzzlebox.domain

/**
 * Soma-cube "different ways" (design gameSpec.records): a filled 3 x 3 x 3 box as a label per cell, reduced over the 48
 * symmetries of the cube (24 rotations x mirror; a mirror swaps the two twists B <-> P). 240 classes in all.
 */
object Symmetry {
    fun canonical(dims: Dims, label: (Cell) -> PieceType): String {
        require(dims.x == dims.y && dims.y == dims.z) { "only cubes" }
        val n = dims.x - 1
        var best: String? = null
        for (mirror in listOf(false, true)) for (r in Orient.ROTS) {
            val sb = CharArray(dims.volume)
            for (c in dims.cells) {
                // centre, rotate, un-centre (doubled coordinates keep it integral)
                val d = Cell(2 * c.x - n, 2 * c.y - n, 2 * c.z - n)
                val m = if (mirror) Cell(-d.x, d.y, d.z) else d
                val p = r * m
                val t = Cell((p.x + n) / 2, (p.y + n) / 2, (p.z + n) / 2)
                var l = label(c)
                if (mirror) l = when (l) { PieceType.B -> PieceType.P; PieceType.P -> PieceType.B; else -> l }
                sb[dims.index(t)] = 'a' + l.ordinal
            }
            val s = String(sb)
            if (best == null || s < best) best = s
        }
        return best!!
    }
}
