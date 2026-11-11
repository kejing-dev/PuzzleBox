package io.embodit.puzzlebox.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game flow through the same calls the volume / panel / debug use (T1..T20 that a JVM can check). */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    class MemStore : GameStore { var g: SavedGame? = null; override fun load() = g; override fun save(game: SavedGame) { g = game }; override fun clear() { g = null } }
    class MemRec : RecordStore { var r = Records(); override fun load() = r; override fun save(r: Records) { this.r = r } }

    companion object {
        fun flow(scope: TestScope, store: GameStore = MemStore(), rec: MemRec = MemRec(), sounds: MutableList<Sound> = ArrayList(), first: Boolean = true): GameFlow {
            var s = Settings(firstRunDone = !first)
            var t = 0L
            val d = StandardTestDispatcher(scope.testScheduler)
            return GameFlow(scope, d, { t += 2000; t }, store, rec, { s }, { s = it }, { snd, _ -> sounds += snd }, { println(it) })
        }
    }

    @Test fun selectTurnDropUndo() = runTest {
        val f = flow(this, first = false); f.start(); f.chooseLevel(Levels.indexOf("M4")); advanceUntilIdle()
        val i = f.state.value.pieces.indexOfFirst { it.type == PieceType.I4 }
        f.tapPiece(i)
        assertEquals(Where.STAND, f.state.value.pieces[i].where)
        val r0 = f.state.value.pieces[i].rot
        f.turn(Turn.YAW); assertEquals(Orient.turn(r0, Turn.YAW), f.state.value.pieces[i].rot)
        assertEquals(0, f.state.value.moves)  // turning is not a step
        f.turn(Turn.YAW); f.turn(Turn.YAW); f.turn(Turn.YAW); f.turn(Turn.YAW) // 5 x 90° = along the box depth
        f.tapPad(0, 1); advanceUntilIdle()
        val p = f.state.value.pieces[i]
        assertEquals(Where.BOX, p.where); assertEquals(1, f.state.value.moves)
        f.undo(); advanceUntilIdle()
        assertEquals(Where.TRAY, f.state.value.pieces[i].where)
        assertEquals(i.let { f.state.value.pieces[it].type.flatRot }, f.state.value.pieces[i].rot)
    }

    @Test fun backToTrayAndSwap() = runTest {
        val f = flow(this, first = false); f.start(); f.chooseLevel(Levels.indexOf("W3")); advanceUntilIdle()
        f.tapPiece(0); f.tapPiece(0)
        assertNull(f.state.value.selected); assertEquals(Where.TRAY, f.state.value.pieces[0].where)
        f.tapPiece(0); f.tapPiece(2)
        assertEquals(2, f.state.value.selected); assertEquals(Where.TRAY, f.state.value.pieces[0].where)
    }

    @Test fun padWithoutABlockSaysPickFirst() = runTest {
        val f = flow(this, first = false); f.start(); advanceUntilIdle()
        f.tapPad(0, 0); assertEquals(Notice.PICK_FIRST, f.state.value.notice)
    }

    @Test fun helpFillsEveryBoxAndTheLidCloses() = runTest {
        val sounds = ArrayList<Sound>()
        for (lv in Levels.ALL) {
            val rec = MemRec()
            val f = flow(this, rec = rec, sounds = sounds, first = false); f.start(); f.chooseLevel(Levels.indexOf(lv.id)); advanceUntilIdle()
            repeat(lv.pieces.size) { f.help(); advanceUntilIdle() }
            assertEquals(lv.id, Phase.SOLVED, f.state.value.phase)
            assertTrue(rec.r.of(lv.id).done)
            assertNull("help used: no best", rec.r.of(lv.id).bestMs)
            if (lv.soma) assertEquals(1, rec.r.somaWays.size)
        }
        assertTrue(Sound.LID in sounds && Sound.SOLVED in sounds)
    }

    /** M17 / QA K2: with [帮我放一块] on the last block, S10 (lid) starts only after that block's fly + fall + bounce. */
    @Test fun helpLastBlockLandsBeforeTheLid() = runTest {
        for (id in listOf("W1", "M4", "S5")) {
            val sounds = ArrayList<Sound>()
            val f = flow(this, sounds = sounds, first = false); f.start(); f.chooseLevel(Levels.indexOf(id)); advanceUntilIdle()
            f.debugPlaceSolution(Levels.byId(id)!!.solution.size - 1); advanceUntilIdle()
            f.help(); runCurrent()
            val st = f.state.value
            assertEquals(id, st.pieces.size, st.placed)
            val i = st.motions.keys.single()
            val total = st.motions.getValue(i).totalMs
            assertEquals("$id: still playing while the last block flies", Phase.PLAY, st.phase)
            advanceTimeBy(total - 1); runCurrent()
            assertEquals("$id: no lid before the block has landed ($total ms)", Phase.PLAY, f.state.value.phase)
            assertTrue(Sound.LID !in sounds)
            advanceUntilIdle()
            assertEquals(id, Phase.SOLVED, f.state.value.phase)
            assertTrue(sounds.indexOf(Sound.DROP) in 0 until sounds.indexOf(Sound.LID))
        }
    }

    /** The same for a hand drop of the last block; the time stops at that move. */
    @Test fun lastHandDropLandsBeforeTheLid() = runTest {
        val f = flow(this, first = false); f.start(); f.chooseLevel(Levels.indexOf("W1")); advanceUntilIdle()
        val sol = Levels.byId("W1")!!.solution
        f.debugPlaceSolution(sol.size - 1); advanceUntilIdle()
        val last = sol.last()
        val i = f.state.value.pieces.indices.first { f.state.value.pieces[it].where == Where.TRAY }
        f.debugSelect(i, Orient.rotFor(last.piece, last.cells)); advanceUntilIdle()
        val a = anchorOf(last.cells)
        f.tapPad(a.first, a.second); runCurrent()
        assertTrue(f.state.value.grid().full)
        assertEquals("no lid while the block falls", Phase.PLAY, f.state.value.phase)
        val t0 = f.state.value.elapsedMs
        advanceUntilIdle()
        assertEquals(Phase.SOLVED, f.state.value.phase)
        assertEquals("the time stops at the last move", t0, f.state.value.elapsedMs)
    }

    @Test fun solvingByHandRecordsTheBest() = runTest {
        val rec = MemRec()
        val f = flow(this, rec = rec, first = false); f.start(); f.chooseLevel(Levels.indexOf("W1")); advanceUntilIdle()
        val sol = Levels.byId("W1")!!.solution
        sol.forEachIndexed { k, s ->
            val i = f.state.value.pieces.indices.first { f.state.value.pieces[it].where == Where.TRAY }
            f.tapPiece(i); advanceUntilIdle()
            val rot = Orient.rotFor(s.piece, s.cells)!!
            // turn to the solution orientation with the tabs (BFS over the turns)
            var cur = f.state.value.pieces[i].rot
            val path = HashMap<Int, List<Turn>>().apply { put(cur, emptyList()) }
            val q = ArrayDeque(listOf(cur))
            while (q.isNotEmpty()) { val r = q.removeFirst(); for (t in Turn.entries) { val n = Orient.turn(r, t); if (n !in path) { path[n] = path[r]!! + t; q += n } } }
            val want = Orient.ROTS.indices.first { Orient.cells(s.piece, it) == Orient.cells(s.piece, rot) && it in path }
            path[want]!!.forEach { f.turn(it); advanceUntilIdle() }
            val a = anchorOf(s.cells)
            f.tapPad(a.first, a.second); advanceUntilIdle()
            assertEquals("step $k", Where.BOX, f.state.value.pieces[i].where)
        }
        assertEquals(Phase.SOLVED, f.state.value.phase)
        assertNotNull(rec.r.of("W1").bestMs)
        assertEquals(2, rec.r.of("W1").bestMoves)
    }

    @Test fun takeOutBlockedAndUndoPutsItBack() = runTest {
        val f = flow(this, first = false); f.start(); f.chooseLevel(Levels.indexOf("M4")); advanceUntilIdle()
        f.debugPlaceSolution(3); advanceUntilIdle()
        val st = f.state.value
        val pinned = st.pieces.indices.first { st.pieces[it].type == PieceType.I4 }
        f.tapPiece(pinned); assertEquals(Notice.TAKE_BLOCKED, f.state.value.notice)
        val free = st.pieces.indices.first { st.pieces[it].where == Where.BOX && st.grid().canTakeOut(it) }
        val cells = st.pieces[free].cells
        f.tapPiece(free); advanceUntilIdle()
        assertEquals(Where.STAND, f.state.value.pieces[free].where); assertEquals(free, f.state.value.selected)
        f.undo(); advanceUntilIdle()
        assertEquals(cells, f.state.value.pieces[free].cells); assertNull(f.state.value.selected)
    }

    @Test fun restartAndSwitchAskFirst() = runTest {
        val f = flow(this, first = false); f.start(); advanceUntilIdle()
        f.debugPlaceSolution(1)
        f.requestRestart(); assertEquals(Confirm.Restart, f.state.value.confirm)
        f.cancelConfirm(); assertEquals(1, f.state.value.placed)
        f.chooseLevel(3); assertTrue(f.state.value.confirm is Confirm.Switch)
        f.confirm(); assertEquals(3, f.state.value.levelIndex); assertEquals(0, f.state.value.placed)
    }

    @Test fun hintGhostIsADroppableNextStepAndStuckIsReported() = runTest {
        val f = flow(this, first = false); f.start(); f.chooseLevel(Levels.indexOf("W2")); advanceUntilIdle()
        f.setHint(true); advanceUntilIdle()
        val h = f.state.value.hint!!
        val r = f.state.value.grid().drop(Orient.cells(h.type, h.rot), h.anchor.first, h.anchor.second)
        assertTrue(r is DropResult.Lands && r.cells.toSet() == h.cells.toSet())
        assertNotNull(f.state.value.hintPiece)
    }

    @Test fun saveAndRestore() = runTest {
        val store = MemStore()
        val f = flow(this, store = store, first = false); f.start(); f.chooseLevel(Levels.indexOf("M2")); advanceUntilIdle()
        f.debugPlaceSolution(2); advanceUntilIdle()
        val g = flow(this, store = store, first = false); g.start(); advanceUntilIdle()
        assertEquals("M2", g.state.value.level.id); assertEquals(2, g.state.value.placed); assertEquals(Notice.RESTORED, g.state.value.notice)
    }

    @Test fun dragPicksColumnsWithHysteresisAndDrops() = runTest {
        val f = flow(this, first = false); f.start(); f.chooseLevel(Levels.indexOf("M4")); advanceUntilIdle()
        val i = f.state.value.pieces.indexOfFirst { it.type == PieceType.O }
        f.debugDrag(i, 1, 2, release = false); advanceUntilIdle()
        assertEquals(1 to 2, f.state.value.drag?.column)
        f.dragEnd(); advanceUntilIdle()
        assertEquals(Where.BOX, f.state.value.pieces[i].where)
        // released far outside the mouth -> back to the stand
        val j = f.state.value.pieces.indexOfFirst { it.type == PieceType.I4 }
        f.dragStart(j); f.dragMove(V3(0.5f, 0f, 0.3f)); f.dragEnd(); advanceUntilIdle()
        assertEquals(Where.STAND, f.state.value.pieces[j].where)
    }
}
