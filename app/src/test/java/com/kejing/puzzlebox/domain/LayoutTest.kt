package com.kejing.puzzlebox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D35 / occlusionCheck, M9 / M14, tray rows, tabs, flight arc, solved spin (design spatialLayout + occlusionCheck). */
class LayoutTest {
    private fun trayBoxes(lv: Level): List<Layout.Aabb> {
        val t = Layout.tray(lv.pieces)
        return lv.pieces.mapIndexed { i, p -> Layout.blockAabb(p, Layout.inTray(p, t.slots[i])) }
    }

    @Test fun trayRowsFitAndTwoHighBlocksAreInFront() {
        for (lv in Levels.ALL) {
            val t = Layout.tray(lv.pieces)
            assertTrue("${lv.id} ${t.rowWidth.toList()}", t.rowWidth.all { it <= Layout.ROW_MAX + 1e-4f })
            t.slots.forEach { s -> if (Orient.size(Orient.cells(lv.pieces[s.index], lv.pieces[s.index].flatRot)).y >= 2) assertEquals(1, s.row) }
            val boxes = trayBoxes(lv)
            for (b in boxes) {
                assertTrue(b.min.x >= -Layout.TRAY_W / 2 && b.max.x <= Layout.TRAY_W / 2)
                assertTrue(b.min.z >= Layout.TRAY_Z0 && b.max.z <= Layout.TRAY_Z1)
            }
            // grown hit boxes (1 cm) never overlap
            for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("${lv.id} $i/$j", boxes[i].grow(0.01f).intersects(boxes[j].grow(0.01f)))
        }
    }

    /**
     * occlusionCheck.trayVsBox (q1-1): from the six seated / standing eyes no tray block covers any front-wall cell centre;
     * every front-wall cell centre is seen from at least one of the three default eyes (almost level, the front tray
     * row may cover the lowest row's lower edge). The plate has its own test (PlateViewTest).
     */
    @Test fun trayBlocksDoNotHideTheBoxFront() {
        for (lv in Levels.ALL) {
            val boxes = trayBoxes(lv)
            val d = lv.dims
            val targets = (0 until d.x).flatMap { x -> (0 until d.y).map { y -> Layout.boxToWorld(Layout.cellLocal(d, Cell(x, y, d.z - 1)) + V3(0f, 0f, Layout.U / 2)) } }
            for ((name, eye) in Layout.EYES) if (name !in Layout.DEFAULT_EYES) for (t in targets) for (b in boxes) assertFalse("${lv.id} $name $t", Layout.segmentHits(eye, t, b))
            for (t in targets) assertTrue("${lv.id} $t", Layout.DEFAULT_EYES.values.any { eye -> boxes.none { Layout.segmentHits(eye, t, it) } })
        }
    }

    @Test fun standBlocksNeverTouchTheTabsAndStayInTheVolume() {
        val tabs = Turn.entries.map { t -> val c = Layout.tabCentre(t); Layout.Aabb(c - V3(Layout.TAB_W / 2, Layout.TAB_H / 2, Layout.TAB_T / 2), c + V3(Layout.TAB_W / 2, Layout.TAB_H / 2, Layout.TAB_T / 2)) }
        for (p in PieceType.entries) for (r in Orient.ROTS.indices) {
            val b = Layout.blockAabb(p, Layout.onStand(p, r))
            tabs.forEach { assertFalse("$p $r", b.intersects(it)) }
            assertTrue(b.max.y <= Layout.TOP && b.max.x <= Layout.VOL_W / 2)
        }
    }

    @Test fun flightArcStaysInsideTheVolume() {
        var top = -1f
        for (lv in Levels.ALL) for (p in lv.pieces.distinct()) for (r in Orient.ROTS.indices) {
            val s = Orient.size(Orient.cells(p, r))
            if (s.x > lv.dims.x || s.z > lv.dims.z) continue
            for (ox in 0..lv.dims.x - s.x) for (oz in 0..lv.dims.z - s.z) {
                val seg = Seg(Layout.onStand(p, r), Layout.aboveMouth(lv.dims, p, r, ox, oz), 300, Seg.Kind.FLY, arc = Layout.ARC)
                for (k in 0..10) {
                    val b = Layout.blockAabb(p, seg.at(k / 10f))
                    top = maxOf(top, b.max.y)
                    assertTrue("${lv.id} $p $r top ${b.max.y}", b.max.y <= 0.19f)
                    assertTrue(b.min.x >= -Layout.VOL_W / 2 && b.max.x <= Layout.VOL_W / 2 && b.min.z >= -Layout.VOL_D / 2)
                }
            }
        }
        println("FLIGHT top $top")
    }

    @Test fun boxAndLiftedSpinStayInTheVolumeAboveTheWedge() {
        for (lv in Levels.ALL) {
            val (mn, mx) = Layout.boxOuterLocal(lv.dims)
            val corners = listOf(mn.x, mx.x).flatMap { x -> listOf(mn.y, mx.y + Layout.LID_DROP).flatMap { y -> listOf(mn.z, mx.z).map { z -> V3(x, y, z) } } }
            val c = Layout.boxCentreLocal(lv.dims)
            for (k in 0..12) {
                val spin = Q4.axis(V3.UP, (2 * Math.PI * k / 12).toFloat())
                val lifted = corners.map { Layout.BOX.apply(c + V3(0f, 0.05f, 0f) + spin.rotate(it - c)) }
                assertTrue(lv.id, lifted.all { it.y <= Layout.TOP + 0.03f && it.y >= -0.16f && it.z >= -Layout.VOL_D / 2 })
                // design solvedSpin (q1-1): the turning box never reaches the sign stand / plate (right edge x -0.145),
                // nor its mirror (N8 fallback: the turn stand side is further out, x >= +0.13)
                val plateRight = Layout.plateCorners().maxOf { it.x }
                assertTrue("${lv.id} spin min x ${lifted.minOf { it.x }}", lifted.all { it.x > plateRight + 0.01f && it.x < -plateRight - 0.01f })
            }
        }
    }

    @Test fun hitTargetsAreAtLeastThreeDegreesFromTheSeat() {
        val eye = Layout.SEATED
        for (t in Turn.entries) {
            val c = Layout.tabCentre(t) + Layout.TAB_HIT_OFFSET
            assertTrue(Layout.angleDeg(eye, c, Layout.TAB_HIT.x) >= 3f && Layout.angleDeg(eye, c, Layout.TAB_HIT.y) >= 3f)
        }
        for (lv in Levels.ALL) {
            val t = Layout.tray(lv.pieces)
            lv.pieces.forEachIndexed { i, p ->
                val b = Layout.blockAabb(p, Layout.inTray(p, t.slots[i])).grow(0.01f)
                val c = (b.min + b.max) * 0.5f
                assertTrue("${lv.id} $p", Layout.angleDeg(eye, c, b.max.x - b.min.x) >= 3f)
            }
        }
    }

    @Test fun mouthCoordinatesRoundTrip() {
        for (lv in Levels.ALL) for (x in 0 until lv.dims.x) for (z in 0 until lv.dims.z) {
            val (fx, fz) = Layout.mouthCoords(lv.dims, Layout.worldToBox(Layout.boxToWorld(Layout.mouthLocal(lv.dims, x, z))))
            assertEquals(x.toFloat(), fx, 1e-3f); assertEquals(z.toFloat(), fz, 1e-3f)
        }
    }

    @Test fun blockPoseMatchesItsCells() {
        val lv = Levels.byId("S5")!!
        for (s in lv.solution) {
            val rot = Orient.rotFor(s.piece, s.cells)!!
            val pose = Layout.inBox(lv.dims, s.piece, rot, Cell(s.cells.minOf { it.x }, s.cells.minOf { it.y }, s.cells.minOf { it.z }))
            val drawn = s.piece.cells.map { c -> pose.apply(V3(c.x * Layout.U, c.y * Layout.U, c.z * Layout.U)) }
            val want = s.cells.map { Layout.boxToWorld(Layout.cellLocal(lv.dims, it)) }
            for (d in drawn) assertTrue(want.any { Layout.dist(it, d) < 1e-4f })
        }
    }
}
