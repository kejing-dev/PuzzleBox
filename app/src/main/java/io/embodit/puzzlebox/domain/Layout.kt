package io.embodit.puzzlebox.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Every metre of the volume (design container.volume.math / spatialLayout / occlusionCheck), volume centre = origin,
 * +X right, +Y up, +Z toward the user, floor y = -0.20. Pure, unit-tested (D35 occlusion, hit angles, flight arc, tabs).
 *
 * The box sits on a walnut wedge and is tilted 30° toward the user about its near-bottom edge (PIVOT): box local frame =
 * origin on that edge (x centred), +Y the box's own up, -Z into the box. Blocks fall along the box's own down.
 */
object Layout {
    const val VOL_W = 0.66f
    const val VOL_H = 0.40f
    const val VOL_D = 0.56f
    const val FLOOR = -0.20f
    const val TOP = 0.20f

    /** Cell pitch u and the visible cube (2 mm seam). */
    const val U = 0.04f
    const val CUBE = 0.038f

    const val TILT_DEG = 30f
    val TILT = (TILT_DEG * PI / 180).toFloat()
    val PIVOT = V3(0f, -0.15f, -0.07f)
    val BOX = Pose(PIVOT, Q4.axis(V3(1f, 0f, 0f), TILT))

    /** Walnut floor plate under the cells, and the wall + corner post margin around them. */
    const val FLOOR_T = 0.01f
    const val MARGIN = 0.01f

    /** Mouth flight: blocks hover this far above the mouth (box up) before they fall (design spatialLayout.pieces). */
    const val HOVER = 0.06f
    const val ARC = 0.012f

    // ------------------------------------------------------------------ box frame

    fun innerW(d: Dims) = d.x * U
    fun innerD(d: Dims) = d.z * U
    fun mouthY(d: Dims) = FLOOR_T + d.y * U

    /** Centre of cell [c] in the box frame. */
    fun cellLocal(d: Dims, c: Cell) = V3((c.x + 0.5f) * U - innerW(d) / 2, FLOOR_T + (c.y + 0.5f) * U, -MARGIN - innerD(d) + (c.z + 0.5f) * U)

    /** Centre of mouth cell (cx, cz) on the mouth plane, box frame. */
    fun mouthLocal(d: Dims, cx: Int, cz: Int) = cellLocal(d, Cell(cx, d.y, cz)).let { V3(it.x, mouthY(d), it.z) }

    /** Continuous mouth coordinates of a box-frame point (cell centres at integers). */
    fun mouthCoords(d: Dims, local: V3): Pair<Float, Float> = ((local.x + innerW(d) / 2) / U - 0.5f) to ((local.z + MARGIN + innerD(d)) / U - 0.5f)

    fun boxToWorld(local: V3) = BOX.apply(local)
    fun worldToBox(w: V3) = BOX.q.conj().rotate(w - PIVOT)

    /** Outer box (floor + walls), box frame: min / max corners. */
    fun boxOuterLocal(d: Dims) = V3(-innerW(d) / 2 - MARGIN, 0f, -innerD(d) - 2 * MARGIN) to V3(innerW(d) / 2 + MARGIN, mouthY(d), 0f)

    /** Centre of the box volume (box frame), the spin axis passes through it. */
    fun boxCentreLocal(d: Dims) = V3(0f, mouthY(d) / 2, -MARGIN - innerD(d) / 2)

    // ------------------------------------------------------------------ blocks

    /**
     * Pose of a block entity (GLB origin = canonical cell (0,0,0) centre) whose normalised cells in orientation [rot] put
     * cell (0,0,0) at [cell0] (a frame-local centre). The rotation is the block's orientation in that frame.
     */
    fun blockPose(type: PieceType, rot: Int, cell0: V3): Pose {
        val s = Orient.shift(type, rot)
        return Pose(cell0 + V3(s.x * U, s.y * U, s.z * U), Q4.of(Orient.ROTS[rot]))
    }

    /** A block resting in the box with its normalised cell (0,0,0) at box cell [origin] (world pose). */
    fun inBox(d: Dims, type: PieceType, rot: Int, origin: Cell): Pose = BOX * blockPose(type, rot, cellLocal(d, origin))

    /** Above mouth cell (ox, oz) of the normalised origin, hovering [HOVER] above the mouth (world). */
    fun aboveMouth(d: Dims, type: PieceType, rot: Int, ox: Int, oz: Int): Pose {
        val c = cellLocal(d, Cell(ox, 0, oz))
        val at = { h: Float -> BOX * blockPose(type, rot, V3(c.x, mouthY(d) + h + U / 2, c.z)) }
        // tall blocks hover lower so the flight arc stays under +0.19 (design occlusionCheck.flightArc, volume top +0.20)
        val top = blockAabb(type, at(HOVER)).max.y
        val h = (HOVER - (top - (FLIGHT_TOP - ARC - 0.002f)).coerceAtLeast(0f)).coerceAtLeast(0.008f)
        return at(h)
    }

    const val FLIGHT_TOP = 0.19f

    // ------------------------------------------------------------------ turn stand + tabs

    val STAND_C = V3(0.21f, -0.12f, -0.08f)
    const val STAND_R = 0.08f

    /** A block on the stand: bottom on the top, footprint centred on the stand. Stand frame = box orientation without the tilt. */
    fun onStand(type: PieceType, rot: Int): Pose {
        val s = Orient.size(Orient.cells(type, rot))
        return blockPose(type, rot, STAND_C + V3(-(s.x - 1) * U / 2, U / 2 + 0.0005f, -(s.z - 1) * U / 2))
    }

    const val TAB_W = 0.06f
    const val TAB_H = 0.04f
    const val TAB_T = 0.01f
    const val TAB_GAP = 0.008f
    const val TAB_Z = 0.015f
    const val TAB_Y = -0.134f
    /** Collider: 6.4 x 5.6 cm, grown down and sideways (top edge stays). */
    val TAB_HIT = V3(0.064f, 0.056f, 0.02f)
    val TAB_HIT_OFFSET = V3(0f, -0.008f, 0f)

    fun tabCentre(t: Turn) = V3(STAND_C.x + (t.ordinal - 1) * (TAB_W + TAB_GAP), TAB_Y, TAB_Z)

    // ------------------------------------------------------------------ tray

    const val TRAY_TOP = -0.185f
    const val TRAY_W = 0.62f
    const val TRAY_Z0 = 0.03f
    const val TRAY_Z1 = 0.27f
    const val ROW_BACK_Z = 0.09f
    const val ROW_FRONT_Z = 0.21f
    const val ROW_MAX = 0.58f

    data class Slot(val index: Int, val row: Int, val x0: Float, val w: Float, val d: Float)

    /** Tray rows (design spatialLayout.tray): 2-high blocks to the front row first, the rest widest first to the shorter row. */
    class Tray(val slots: List<Slot>, val rowWidth: FloatArray)

    fun tray(types: List<PieceType>): Tray {
        val dims = types.map { Orient.size(Orient.cells(it, it.flatRot)) }
        val rows = arrayOf(ArrayList<Int>(), ArrayList<Int>())
        fun width(row: List<Int>): Float {
            var w = 0f
            row.forEachIndexed { k, i -> if (k > 0) w += gap(dims[row[k - 1]], dims[i]); w += dims[i].x * U }
            return w
        }
        val order = types.indices.sortedWith(compareBy({ -dims[it].y }, { -dims[it].x }, { it }))
        for (i in order) {
            val row = if (dims[i].y >= 2) 1 else if (width(rows[1]) < width(rows[0])) 1 else 0
            rows[row] += i
        }
        val slots = ArrayList<Slot>()
        val widths = FloatArray(2)
        for (r in 0..1) {
            val w = width(rows[r]); widths[r] = w
            var x = -w / 2
            rows[r].forEachIndexed { k, i ->
                if (k > 0) x += gap(dims[rows[r][k - 1]], dims[i])
                slots += Slot(i, r, x, dims[i].x * U, dims[i].z * U)
                x += dims[i].x * U
            }
        }
        return Tray(slots.sortedBy { it.index }, widths)
    }

    private fun gap(a: Dims, b: Dims) = max(0.04f, 0.3f * max(max(a.x, a.z), max(b.x, b.z)) * U)

    /** A block resting in its tray slot (always the flat orientation). */
    fun inTray(type: PieceType, slot: Slot): Pose {
        val zc = if (slot.row == 0) ROW_BACK_Z else ROW_FRONT_Z
        return blockPose(type, type.flatRot, V3(slot.x0 + U / 2, TRAY_TOP + U / 2 + 0.0005f, zc - slot.d / 2 + U / 2))
    }

    // ------------------------------------------------------------------ name plate (q1-1: left of the box on a walnut sign stand), lid

    /**
     * Design spatialLayout.standPlate (q1-1, v0.1.1): a 0.18 x 0.07 m brass plate on a small walnut stand left of the box,
     * mirroring the turn stand, centre (-0.235, -0.050, -0.040), leaning back 15° (top edge away from the user). Always two
     * lines. In S15 it takes a 0.18 x 0.11 collider with the same centre and lean (2 cm more above and below).
     */
    const val PLATE_W = 0.18f
    const val PLATE_H = 0.07f
    val PLATE_C = V3(-0.235f, -0.050f, -0.040f)
    const val PLATE_LEAN_DEG = 15f
    /** About +X by -15°: the plate's up leans toward -Z (away from the user). */
    val PLATE_Q = Q4.axis(V3(1f, 0f, 0f), (-PLATE_LEAN_DEG * PI / 180).toFloat())
    const val PLATE_PX_W = 1024
    const val PLATE_PX_H = 400
    /** S15 collider (design standPlate: 0.18 x 0.11, same centre and lean), 12 mm thick. */
    const val PLATE_HIT_W = 0.18f
    const val PLATE_HIT_H = 0.11f
    const val PLATE_HIT_T = 0.012f

    private fun plateAt(sx: Float, sy: Float, w: Float, h: Float) = PLATE_C + PLATE_Q.rotate(V3(sx * w / 2, sy * h / 2, 0f))

    fun plateCorners(): List<V3> = listOf(-1f, 1f).flatMap { sx -> listOf(-1f, 1f).map { sy -> plateAt(sx, sy, PLATE_W, PLATE_H) } }

    /** 5 x 3 sample points over the plate face (design occlusionCheck.standPlate). */
    fun plateSamples(): List<V3> = (0..4).flatMap { i -> (0..2).map { j -> plateAt(-1f + i * 0.5f, -1f + j * 1f, PLATE_W, PLATE_H) } }

    /** S15 collider: 4 corners + the centre. */
    fun plateHitPoints(): List<V3> = listOf(-1f, 1f).flatMap { sx -> listOf(-1f, 1f).map { sy -> plateAt(sx, sy, PLATE_HIT_W, PLATE_HIT_H) } } + PLATE_C

    /** The sign stand under the plate (tools/build_models.py signstand.glb): base on the floor, x -0.325..-0.145, z -0.070..-0.020. */
    val SIGN_BASE = Aabb(V3(-0.325f, FLOOR, -0.070f), V3(-0.145f, FLOOR + 0.012f, -0.020f))

    /** Ray (eye -> through) against the plate's S15 collider (an oriented box): distance along the unit ray, or null. */
    fun rayPlate(eye: V3, dir: V3): Float? {
        val inv = PLATE_Q.conj()
        val o = inv.rotate(eye - PLATE_C)
        val d = inv.rotate(dir)
        return rayAabb(o, d, Aabb(V3(-PLATE_HIT_W / 2, -PLATE_HIT_H / 2, -PLATE_HIT_T / 2), V3(PLATE_HIT_W / 2, PLATE_HIT_H / 2, PLATE_HIT_T / 2)))
    }

    const val LID_T = 0.008f
    const val LID_DROP = 0.15f

    // ------------------------------------------------------------------ eyes (design occlusionCheck.eyePositions)

    /**
     * The nine eyes (design occlusionCheck.eyePositions, q1-1): the system default placement (0, +0.06, +1.75) and its
     * left / right 30° about the volume's vertical axis come first (QA #1 log, the most common view), then seated /
     * standing and their 30° yaws.
     */
    val DEFAULT_EYES: Map<String, V3> = linkedMapOf(
        "默认" to V3(0f, 0.06f, 1.75f), "默认左30" to V3(-0.875f, 0.06f, 1.516f), "默认右30" to V3(0.875f, 0.06f, 1.516f),
    )
    val EYES: Map<String, V3> = DEFAULT_EYES + linkedMapOf(
        "坐" to V3(0f, 0.29f, 0.95f), "站" to V3(0f, 0.69f, 0.95f),
        "坐左30" to V3(-0.48f, 0.29f, 0.82f), "坐右30" to V3(0.48f, 0.29f, 0.82f),
        "站左30" to V3(-0.48f, 0.69f, 0.82f), "站右30" to V3(0.48f, 0.69f, 0.82f),
    )
    val DEFAULT_EYE = DEFAULT_EYES.getValue("默认")
    val SEATED = EYES.getValue("坐")

    // ------------------------------------------------------------------ geometry helpers (tests + scene)

    data class Aabb(val min: V3, val max: V3) {
        fun grow(m: Float) = Aabb(min - V3(m, m, m), max + V3(m, m, m))
        fun intersects(o: Aabb) = min.x < o.max.x && max.x > o.min.x && min.y < o.max.y && max.y > o.min.y && min.z < o.max.z && max.z > o.min.z
        val top get() = max.y
    }

    fun aabbOf(points: List<V3>) = Aabb(V3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }), V3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))

    /** The world corners of every cube of [type] at [pose] (cube = [size] metres). */
    fun cubeCorners(type: PieceType, pose: Pose, size: Float = CUBE): List<V3> = type.cells.flatMap { c ->
        val ctr = V3(c.x * U, c.y * U, c.z * U)
        listOf(-1f, 1f).flatMap { a -> listOf(-1f, 1f).flatMap { b -> listOf(-1f, 1f).map { e -> pose.apply(ctr + V3(a * size / 2, b * size / 2, e * size / 2)) } } }
    }

    fun blockAabb(type: PieceType, pose: Pose, size: Float = CUBE) = aabbOf(cubeCorners(type, pose, size))

    /** Does the segment a-b pass through the box? (slab test) */
    fun segmentHits(a: V3, b: V3, box: Aabb): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        val ax = floatArrayOf(a.x, a.y, a.z); val dx = floatArrayOf(d.x, d.y, d.z)
        val mn = floatArrayOf(box.min.x, box.min.y, box.min.z); val mx = floatArrayOf(box.max.x, box.max.y, box.max.z)
        for (i in 0..2) {
            if (kotlin.math.abs(dx[i]) < 1e-9f) { if (ax[i] < mn[i] || ax[i] > mx[i]) return false; continue }
            var ta = (mn[i] - ax[i]) / dx[i]; var tb = (mx[i] - ax[i]) / dx[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = max(t0, ta); t1 = min(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    /** Ray (origin [o], direction [d]) against an axis-aligned box: entry distance in units of |d| (0 if inside), or null. */
    fun rayAabb(o: V3, d: V3, box: Aabb): Float? {
        var t0 = 0f; var t1 = Float.MAX_VALUE
        val ax = floatArrayOf(o.x, o.y, o.z); val dx = floatArrayOf(d.x, d.y, d.z)
        val mn = floatArrayOf(box.min.x, box.min.y, box.min.z); val mx = floatArrayOf(box.max.x, box.max.y, box.max.z)
        for (i in 0..2) {
            if (kotlin.math.abs(dx[i]) < 1e-9f) { if (ax[i] < mn[i] || ax[i] > mx[i]) return null; continue }
            var ta = (mn[i] - ax[i]) / dx[i]; var tb = (mx[i] - ax[i]) / dx[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = max(t0, ta); t1 = min(t1, tb)
            if (t0 > t1) return null
        }
        return t0
    }

    /** Segment a-b against the tilted box (outer box incl. walls + lid travel not included), tested in the box frame. */
    fun segmentHitsBox(a: V3, b: V3, d: Dims, pose: Pose = BOX): Boolean {
        val inv = pose.q.conj()
        val (mn, mx) = boxOuterLocal(d)
        return segmentHits(inv.rotate(a - pose.p), inv.rotate(b - pose.p), Aabb(mn, mx))
    }

    /** Angular size (degrees) of a [w] x [h] target centred at [c] seen from [eye] (small-angle). */
    fun angleDeg(eye: V3, c: V3, extent: Float): Float = (2 * atan2(extent / 2, (c - eye).length) * 180 / PI).toFloat()

    fun dist(a: V3, b: V3) = sqrt((a - b).dot(a - b))
}
