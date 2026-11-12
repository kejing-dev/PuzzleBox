package io.embodit.puzzlebox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design T2 / T3 / T4 / T5 / M13 / M6 + levels: rotations, drop rule, holes, column ticks, take-out, solver. */
class RulesTest {
    @Test fun twentyFourRotationsAllReachableWithTheThreeTabs() {
        assertEquals(24, Orient.ROTS.size)
        val seen = hashSetOf(0); var frontier = listOf(0)
        while (frontier.isNotEmpty()) frontier = frontier.flatMap { r -> Turn.entries.map { Orient.turn(r, it) } }.filter { seen.add(it) }
        assertEquals(24, seen.size)
    }

    @Test fun eachTabFourTimesIsTheSame() {
        for (t in Turn.entries) for (r in Orient.ROTS.indices) { var x = r; repeat(4) { x = Orient.turn(x, t) }; assertEquals(r, x) }
    }

    @Test fun tabDirectionsMatchTheDesign() {
        assertEquals(Cell(0, 0, 1), Turn.YAW.mat * Cell(1, 0, 0))   // clockwise from above: right -> toward the user
        assertEquals(Cell(0, 0, 1), Turn.PITCH.mat * Cell(0, 1, 0)) // top toward the user
        assertEquals(Cell(1, 0, 0), Turn.ROLL.mat * Cell(0, 1, 0))  // top to the right
    }

    @Test fun twistsNeverTurnIntoEachOther() {
        val b = Orient.ROTS.indices.map { Orient.cells(PieceType.B, it) }.toSet()
        val p = Orient.ROTS.indices.map { Orient.cells(PieceType.P, it) }.toSet()
        assertTrue(b.intersect(p).isEmpty())
    }

    @Test fun flatOrientationIsLowest() {
        for (t in PieceType.entries) {
            val h = Orient.size(Orient.cells(t, t.flatRot)).y
            assertEquals(if (t in setOf(PieceType.A, PieceType.B, PieceType.P)) 2 else 1, h)
        }
    }

    @Test fun anchorIsNearestTheFootprintCentre() {
        assertEquals(0 to 0, anchorOf(Orient.cells(PieceType.I2, 0)))       // tie -> smaller x
        assertEquals(1 to 0, anchorOf(Orient.cells(PieceType.I3, 0)))
        assertEquals(1 to 0, anchorOf(Orient.cells(PieceType.T, 0)))
    }

    @Test fun dropFallsToFirstContactAndClampsInsideTheMouth() {
        val g = BoxGrid(Dims(3, 2, 2))
        val r = g.drop(Orient.cells(PieceType.I3, 0), 2, 0) as DropResult.Lands
        assertEquals(setOf(Cell(0, 0, 0), Cell(1, 0, 0), Cell(2, 0, 0)), r.cells.toSet())  // anchor 1 aimed at 2 -> clamped back
        g.put(0, r.cells)
        val r2 = g.drop(Orient.cells(PieceType.I2, 0), 0, 0) as DropResult.Lands
        assertEquals(setOf(Cell(0, 1, 0), Cell(1, 1, 0)), r2.cells.toSet())
    }

    @Test fun sticksOutAndTooBigAreRejected() {
        val g = BoxGrid(Dims(2, 2, 2))
        assertEquals(DropResult.TooBig, g.drop(Orient.cells(PieceType.I3, 0), 0, 0))
        val up = Orient.ROTS.indices.first { Orient.size(Orient.cells(PieceType.I3, it)).y == 3 }
        assertTrue(g.drop(Orient.cells(PieceType.I3, up), 0, 0) is DropResult.SticksOut)
        g.put(0, listOf(Cell(0, 0, 0), Cell(0, 1, 0)))
        assertTrue(g.drop(Orient.cells(PieceType.I2, 0), 0, 0) is DropResult.SticksOut) // lands on top of a full column
    }

    @Test fun holesTicksAndTakeOut() {
        val g = BoxGrid(Dims(2, 2, 1))
        g.put(0, listOf(Cell(0, 0, 0)))                    // one cube at the bottom left
        val r = g.drop(Orient.cells(PieceType.I2, 0), 0, 0) as DropResult.Lands
        g.put(1, r.cells)                                  // the domino rests on it and covers (1,0,0)
        assertEquals(listOf(Cell(1, 0, 0)), g.holes())
        assertEquals(0, g.depth(0, 0)); assertEquals(0, g.depth(1, 0))
        assertFalse(g.canTakeOut(0)); assertTrue(g.canTakeOut(1))
        assertEquals(2, BoxGrid(Dims(2, 2, 1)).depth(1, 0))
    }

    @Test fun everyDesignSolutionDropsInOrderAndFillsTheBox() {
        for (lv in Levels.ALL) {
            val g = BoxGrid(lv.dims)
            assertEquals(lv.dims.volume, lv.pieces.sumOf { it.cells.size })
            lv.solution.forEachIndexed { i, s ->
                val rot = Orient.rotFor(s.piece, s.cells)
                assertNotNull("${lv.id} step $i", rot)
                val shape = Orient.cells(s.piece, rot!!)
                val anchor = anchorOf(s.cells.map { it })
                val r = g.drop(shape, anchor.first, anchor.second)
                assertTrue("${lv.id} step $i $r", r is DropResult.Lands && r.cells.toSet() == s.cells.toSet())
                g.put(i, s.cells)
                // ticks always match the real column depth (M13)
                for (x in 0 until lv.dims.x) for (z in 0 until lv.dims.z) {
                    val d = g.depth(x, z)
                    assertEquals((0 until lv.dims.y).count { y -> (y until lv.dims.y).none { g.filled(Cell(x, it, z)) } }, d)
                }
            }
            assertTrue(lv.id, g.full)
        }
    }

    @Test fun solverFillsEveryEmptyBoxAndFastEnough() {
        for (lv in Levels.ALL) {
            val t0 = System.nanoTime()
            val sol = Solver(lv.dims).solve(0L, lv.pieces)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertNotNull(lv.id, sol)
            println("SOLVER ${lv.id} ${lv.dims} ${ms} ms")
            val g = BoxGrid(lv.dims)
            sol!!.forEachIndexed { i, p ->
                val r = g.drop(Orient.cells(p.type, p.rot), p.anchor.first, p.anchor.second)
                assertTrue("${lv.id} $i", r is DropResult.Lands && r.cells.toSet() == p.cells.toSet())
                g.put(i, p.cells)
            }
            assertTrue(g.full)
            assertTrue("${lv.id} took $ms ms", ms < 1000)
        }
    }

    @Test fun solverReportsADeadBox() {
        val lv = Levels.byId("W1")!!
        // one O flat on the bottom shifted is impossible; use a hole: a domino-free stack that seals a cell
        val g = BoxGrid(lv.dims)
        g.put(0, listOf(Cell(0, 1, 0), Cell(1, 1, 0), Cell(0, 1, 1), Cell(1, 1, 1)))
        val t0 = System.nanoTime()
        assertEquals(null, Solver(lv.dims).solve(g.mask(), listOf(PieceType.O)))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
        val s5 = Levels.byId("S5")!!
        val g5 = BoxGrid(s5.dims); g5.put(0, listOf(Cell(0, 1, 0), Cell(1, 1, 0), Cell(2, 1, 0)))
        val t1 = System.nanoTime()
        assertEquals(null, Solver(s5.dims).solve(g5.mask(), s5.pieces.drop(1).take(6)))
        println("SOLVER dead S5 ${(System.nanoTime() - t1) / 1_000_000} ms")
    }

    @Test fun somaCanonicalIsSymmetryInvariant() {
        val s5 = Levels.byId("S5")!!
        val label = HashMap<Cell, PieceType>()
        s5.solution.forEach { st -> st.cells.forEach { label[it] = st.piece } }
        val k = Symmetry.canonical(s5.dims) { label[it]!! }
        val turned = HashMap<Cell, PieceType>()
        val r = Orient.ROTS[5]
        label.forEach { (c, t) -> val d = r * Cell(2 * c.x - 2, 2 * c.y - 2, 2 * c.z - 2); turned[Cell((d.x + 2) / 2, (d.y + 2) / 2, (d.z + 2) / 2)] = t }
        assertEquals(k, Symmetry.canonical(s5.dims) { turned[it]!! })
    }

    @Test fun saveRoundTrip() {
        val lv = Levels.byId("M4")!!
        val s = lv.solution[0]
        val pieces = lv.pieces.mapIndexed { i, t -> if (i == 0) PieceState(t, Where.BOX, Orient.rotFor(t, s.cells)!!, s.cells) else PieceState(t, Where.TRAY, t.flatRot) }
        val g = SavedGame("M4", pieces, null, 1, 1234, false, false, true, listOf(UndoEntry.Drop(0, s.cells, pieces[0].rot)))
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
    }
}
