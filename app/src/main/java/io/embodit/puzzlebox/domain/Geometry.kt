package io.embodit.puzzlebox.domain

/**
 * Box cell coordinates (design gameSpec.units): x to the right, y up, z from the box's back wall (0) toward the user.
 * dims = [X wide, Y high, Z deep]. Everything about the rules is computed in these integer coordinates; the 30° tilt of
 * the box is only how it is shown (Layout).
 */
data class Cell(val x: Int, val y: Int, val z: Int) {
    operator fun plus(o: Cell) = Cell(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Cell) = Cell(x - o.x, y - o.y, z - o.z)
    override fun toString() = "$x,$y,$z"

    companion object {
        fun parse(s: String): Cell { val f = s.split(","); return Cell(f[0].trim().toInt(), f[1].trim().toInt(), f[2].trim().toInt()) }
    }
}

data class Dims(val x: Int, val y: Int, val z: Int) {
    val volume get() = x * y * z
    fun contains(c: Cell) = c.x in 0 until x && c.y in 0 until y && c.z in 0 until z
    fun index(c: Cell) = (c.y * z + c.z) * x + c.x
    fun cellAt(i: Int) = Cell(i % x, i / (x * z), (i / x) % z)
    val cells: List<Cell> get() = (0 until volume).map { cellAt(it) }
    override fun toString() = "${x}x${y}x${z}"
}

/** The eleven block shapes (design pieces): canonical cells + lacquer colour + names. */
enum class PieceType(val zh: String, val en: String, val color: Long, cells: List<Cell>) {
    I2("短条", "Domino", 0xFFE9C46A, listOf(Cell(0, 0, 0), Cell(1, 0, 0))),
    I3("长条", "Bar", 0xFFF4A261, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(2, 0, 0))),
    I4("大长条", "Long bar", 0xFFE76F51, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(2, 0, 0), Cell(3, 0, 0))),
    O("方块", "Square", 0xFF2A9D8F, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(0, 0, 1), Cell(1, 0, 1))),
    V("小拐", "Corner", 0xFF8AB17D, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(0, 0, 1))),
    L("拐子", "L", 0xFF4D7CC7, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(2, 0, 0), Cell(0, 0, 1))),
    T("丁字", "T", 0xFFD1495B, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(2, 0, 0), Cell(1, 0, 1))),
    Z("歪条", "Zigzag", 0xFF7B5EA7, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(1, 0, 1), Cell(2, 0, 1))),
    A("三叉", "Branch", 0xFF5C946E, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(0, 0, 1), Cell(0, 1, 0))),
    B("右螺旋", "Right twist", 0xFFF7B32B, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(1, 0, 1), Cell(1, 1, 1))),
    P("左螺旋", "Left twist", 0xFF3FA7D6, listOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(0, 0, 1), Cell(0, 1, 1)));

    /** Canonical cells exactly as the design lists them (also the GLB's cube layout, tools/build_models.py). */
    val cells: List<Cell> = cells

    /** The orientation a block always has in the tray ("最平", design spatialLayout.tray). */
    val flatRot: Int by lazy { Orient.flatRot(this) }

    companion object {
        fun parse(s: String): PieceType? = entries.firstOrNull { it.name == s }
    }
}

/** 3 x 3 integer rotation matrix (row-major). */
data class Mat3(val m: IntArray) {
    operator fun times(c: Cell) = Cell(
        m[0] * c.x + m[1] * c.y + m[2] * c.z,
        m[3] * c.x + m[4] * c.y + m[5] * c.z,
        m[6] * c.x + m[7] * c.y + m[8] * c.z,
    )

    operator fun times(o: Mat3): Mat3 {
        val r = IntArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = (0..2).sumOf { k -> m[i * 3 + k] * o.m[k * 3 + j] }
        return Mat3(r)
    }

    override fun equals(other: Any?) = other is Mat3 && m.contentEquals(other.m)
    override fun hashCode() = m.contentHashCode()

    companion object {
        val ID = Mat3(intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1))
    }
}

/** The three wooden tabs (design input.tabs / gameSpec.orientation). */
enum class Turn(val zh: String, val en: String) {
    /** 转: about the vertical axis, clockwise seen from above (+X -> +Z -> -X). */
    YAW("转", "Turn"),
    /** 前翻: about the left-right axis, the top face toward the user (+Y -> +Z). */
    PITCH("前翻", "Flip fwd"),
    /** 侧翻: about the front-back axis, the top face to the right (+Y -> +X). */
    ROLL("侧翻", "Flip side");

    val mat: Mat3
        get() = when (this) {
            YAW -> Mat3(intArrayOf(0, 0, -1, 0, 1, 0, 1, 0, 0))
            PITCH -> Mat3(intArrayOf(1, 0, 0, 0, 0, -1, 0, 1, 0))
            ROLL -> Mat3(intArrayOf(0, 1, 0, -1, 0, 0, 0, 0, 1))
        }

    companion object {
        fun parse(s: String): Turn? = when (s.lowercase()) { "yaw", "turn" -> YAW; "pitch", "fwd" -> PITCH; "roll", "side" -> ROLL; else -> null }
    }
}

/**
 * The 24 proper rotations (no mirrors: the two twists are different blocks). Index 0 = identity; the list is the
 * closure of the three tab turns, so every index is reachable with the tabs (unit-tested).
 */
object Orient {
    val ROTS: List<Mat3> by lazy {
        val out = arrayListOf(Mat3.ID)
        var i = 0
        while (i < out.size) {
            for (t in Turn.entries) { val n = t.mat * out[i]; if (n !in out) out += n }
            i++
        }
        check(out.size == 24) { "rotation group has ${out.size}" }
        out
    }

    /** Index of [m] in [ROTS]. */
    fun indexOf(m: Mat3): Int = ROTS.indexOf(m).also { require(it >= 0) }

    /** The rotation after one tab press. */
    fun turn(rot: Int, t: Turn): Int = indexOf(t.mat * ROTS[rot])

    /** Cells of [type] in orientation [rot], shifted so the minimum corner is (0,0,0). */
    fun cells(type: PieceType, rot: Int): List<Cell> = normalize(type.cells.map { ROTS[rot] * it })

    /** The shift that normalises the rotated canonical cells: rotated + shift = normalised. */
    fun shift(type: PieceType, rot: Int): Cell {
        val r = type.cells.map { ROTS[rot] * it }
        return Cell(-r.minOf { it.x }, -r.minOf { it.y }, -r.minOf { it.z })
    }

    fun normalize(cs: List<Cell>): List<Cell> {
        val mx = cs.minOf { it.x }; val my = cs.minOf { it.y }; val mz = cs.minOf { it.z }
        return cs.map { Cell(it.x - mx, it.y - my, it.z - mz) }.sortedWith(compareBy({ it.y }, { it.z }, { it.x }))
    }

    fun size(cs: List<Cell>) = Dims(cs.maxOf { it.x } + 1, cs.maxOf { it.y } + 1, cs.maxOf { it.z } + 1)

    /** "最平": lowest height; ties -> widest along X (left-right); then shallowest; then the lowest rotation index. */
    fun flatRot(type: PieceType): Int = ROTS.indices.minWith(compareBy<Int>({ size(cells(type, it)).y }, { -size(cells(type, it)).x }, { size(cells(type, it)).z }, { it }))

    /** Distinct normalised cell sets of a type (the solver's orientations) with one rotation index each. */
    fun distinct(type: PieceType): List<Pair<Int, List<Cell>>> {
        val seen = LinkedHashMap<List<Cell>, Int>()
        for (r in ROTS.indices) seen.putIfAbsent(cells(type, r), r)
        return seen.entries.map { it.value to it.key }
    }

    /** A rotation index of [type] whose normalised cells equal [target] (normalised), or null. */
    fun rotFor(type: PieceType, target: List<Cell>): Int? {
        val n = normalize(target)
        return ROTS.indices.firstOrNull { cells(type, it) == n }
    }
}

/** Footprint seen from above (along the box's own down): distinct (x, z) of the cells. */
fun footprint(cells: List<Cell>): List<Pair<Int, Int>> = cells.map { it.x to it.z }.distinct().sortedWith(compareBy({ it.first }, { it.second }))

/**
 * The anchor of a footprint (design dropRule): the footprint cell nearest the footprint's geometric centre; ties -> smaller
 * x, then smaller z. This is the column the block lands in when you tap a mouth cell, and where the white dot is drawn.
 */
fun anchorOf(cells: List<Cell>): Pair<Int, Int> {
    val fp = footprint(cells)
    val cx = fp.sumOf { it.first }.toDouble() / fp.size
    val cz = fp.sumOf { it.second }.toDouble() / fp.size
    return fp.minWith(compareBy<Pair<Int, Int>>({ (it.first - cx) * (it.first - cx) + (it.second - cz) * (it.second - cz) }, { it.first }, { it.second }))
}
