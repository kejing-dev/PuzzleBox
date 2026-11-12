package io.embodit.puzzlebox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos

/**
 * q1-1 (design occlusionCheck.standPlate, M9 / M14 / M15): the name plate on its sign stand left of the box is seen whole
 * and hit first from all nine eyes, the three system-default ones first. Also run with the plate / turn stand / tabs
 * mirrored left-right (N8 fallback: "整组左右对调，单测同一套").
 */
class PlateViewTest {
    companion object {
        /** Every collider / solid that could stand between an eye and the plate, named like the scene holders. */
        fun obstacles(lv: Level, sx: Float = 1f): List<Pair<String, Layout.Aabb>> {
            fun mir(b: Layout.Aabb) = if (sx > 0) b else Layout.Aabb(V3(-b.max.x, b.min.y, b.min.z), V3(-b.min.x, b.max.y, b.max.z))
            val out = ArrayList<Pair<String, Layout.Aabb>>()
            val t = Layout.tray(lv.pieces)
            // tray blocks: the visible block grown 1 cm (their colliders, design hitTargets)
            lv.pieces.forEachIndexed { i, p -> out += "pc:$i" to Layout.blockAabb(p, Layout.inTray(p, t.slots[i])).grow(0.01f) }
            // the turn stand (disc + bracket) and every block type in every orientation standing on it (grown 1 cm)
            out += "stand" to mir(Layout.Aabb(V3(Layout.STAND_C.x - Layout.STAND_R, Layout.FLOOR, Layout.STAND_C.z - Layout.STAND_R), V3(Layout.STAND_C.x + Layout.STAND_R, Layout.STAND_C.y, Layout.STAND_C.z + Layout.STAND_R)))
            out += "bracket" to mir(Layout.Aabb(V3(0.105f, -0.170f, 0.004f), V3(0.315f, -0.154f, 0.026f)))
            for (p in lv.pieces.distinct()) for (r in Orient.ROTS.indices) out += "standBlock:$p:$r" to mir(Layout.blockAabb(p, Layout.onStand(p, r)).grow(0.01f))
            for (tn in Turn.entries) {
                val c = Layout.tabCentre(tn) + Layout.TAB_HIT_OFFSET
                out += "tab:${tn.name.lowercase()}" to mir(Layout.Aabb(c - Layout.TAB_HIT * 0.5f, c + Layout.TAB_HIT * 0.5f))
            }
            return out
        }

        fun mirror(v: V3, sx: Float) = V3(v.x * sx, v.y, v.z)

        /** Angle (degrees) between the directions from [eye] to [a] and to [b]. */
        fun angle(eye: V3, a: V3, b: V3): Float {
            val u = a - eye; val v = b - eye
            return (acos((u.dot(v) / (u.length * v.length)).coerceIn(-1f, 1f)) * 180 / PI).toFloat()
        }

        /** The S15 collider's angular width and height from [eye] (edge mid-points, leaning back 15°). */
        fun plateHitAngles(eye: V3, sx: Float = 1f): Pair<Float, Float> {
            val q = Layout.PLATE_Q
            val c = mirror(Layout.PLATE_C, sx)
            val w = Layout.PLATE_HIT_W / 2; val h = Layout.PLATE_HIT_H / 2
            return angle(eye, c + q.rotate(V3(-w, 0f, 0f)), c + q.rotate(V3(w, 0f, 0f))) to angle(eye, c + q.rotate(V3(0f, -h, 0f)), c + q.rotate(V3(0f, h, 0f)))
        }
    }

    private fun check(sx: Float) {
        val points = (Layout.plateSamples() + Layout.plateHitPoints()).map { mirror(it, sx) }
        var minAngle = Float.MAX_VALUE
        for (lv in Levels.ALL) {
            val obs = obstacles(lv, sx)
            for ((name, eye) in Layout.EYES) {
                for (pt in points) {
                    for ((what, b) in obs) assertFalse("${lv.id} $name sx=$sx -> $pt blocked by $what", Layout.segmentHits(eye, pt, b))
                    assertFalse("${lv.id} $name sx=$sx -> $pt blocked by the box", Layout.segmentHitsBox(eye, pt, lv.dims))
                    assertFalse("${lv.id} $name -> $pt blocked by the box lifted", Layout.segmentHitsBox(eye, pt, lv.dims, Pose(Layout.BOX.p + Layout.BOX.q.rotate(V3(0f, 0.05f, 0f)), Layout.BOX.q)))
                }
            }
        }
        for ((name, eye) in Layout.EYES) {
            val (aw, ah) = plateHitAngles(eye, sx)
            println("PLATE sx=$sx $name collider ${"%.1f".format(aw)}° x ${"%.1f".format(ah)}°  dist ${"%.2f".format(Layout.dist(eye, mirror(Layout.PLATE_C, sx)))} m")
            assertTrue("$name sx=$sx $aw x $ah", aw >= 3f && ah >= 3f)
            if (name in Layout.DEFAULT_EYES) minAngle = minOf(minAngle, aw, ah)
        }
        println("PLATE sx=$sx default eyes min angle $minAngle°")
    }

    /** Design acceptance M9 / M14 (q1-1): 9 eyes x 15 boxes, plate face 5 x 3 + collider 4 corners + centre, nothing in between; >= 3° x 3°. */
    @Test fun plateVisibleAndHittableFromDefaultView() = check(1f)

    /** N8 fallback: turn stand + tabs and the plate swapped left-right pass the same checks. */
    @Test fun plateVisibleAndHittableMirrored() = check(-1f)

    /** The ray from each default eye to the plate centre meets the plate's S15 collider before any other collider. */
    @Test fun plateIsTheFirstHitFromTheDefaultEyes() {
        for (lv in Levels.ALL) for ((name, eye) in Layout.DEFAULT_EYES) {
            val dir = Layout.PLATE_C - eye
            val tPlate = Layout.rayPlate(eye, dir)
            assertTrue("$name misses the plate", tPlate != null && tPlate < 1f)
            for ((what, b) in obstacles(lv)) Layout.rayAabb(eye, dir, b)?.let { t -> assertTrue("${lv.id} $name: $what ($t) before the plate ($tPlate)", t > tPlate!!) }
            assertEquals(firstHit(lv, eye, dir), "plate")
        }
    }

    /** Name of the first collider along the ray (plate / tray blocks / stand blocks / tabs). */
    fun firstHit(lv: Level, eye: V3, dir: V3): String? {
        val hits = obstacles(lv).filter { !it.first.startsWith("stand") && it.first != "bracket" }.mapNotNull { (n, b) -> Layout.rayAabb(eye, dir, b)?.let { n to it } } +
            listOfNotNull(Layout.rayPlate(eye, dir)?.let { "plate" to it })
        return hits.minByOrNull { it.second }?.first
    }

    /** The plate and its stand stay inside the volume, behind the tray and left of the box. */
    @Test fun plateAndStandFitTheVolume() {
        val pts = Layout.plateCorners() + Layout.plateHitPoints()
        assertTrue(pts.all { it.x >= -Layout.VOL_W / 2 && it.y >= Layout.FLOOR && it.z <= Layout.TRAY_Z0 })
        val b = Layout.SIGN_BASE
        assertTrue(b.min.x >= -Layout.VOL_W / 2 && b.max.z <= Layout.TRAY_Z0)
        // the plate face: x -0.325..-0.145, y -0.084..-0.016, z -0.049..-0.031 (design standPlate)
        val c = Layout.plateCorners()
        assertEquals(-0.325f, c.minOf { it.x }, 1e-3f); assertEquals(-0.145f, c.maxOf { it.x }, 1e-3f)
        assertEquals(-0.084f, c.minOf { it.y }, 1e-3f); assertEquals(-0.016f, c.maxOf { it.y }, 1e-3f)
        assertEquals(-0.049f, c.minOf { it.z }, 1e-3f); assertEquals(-0.031f, c.maxOf { it.z }, 1e-3f)
        // top edge leans away from the user
        assertTrue(c.filter { it.y > -0.05f }.all { it.z < -0.04f })
    }
}
