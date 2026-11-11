package io.embodit.puzzlebox.ui.board

import io.embodit.puzzlebox.domain.FlowTest
import io.embodit.puzzlebox.domain.Levels
import io.embodit.puzzlebox.domain.PlayState
import io.embodit.puzzlebox.domain.Turn
import io.embodit.puzzlebox.domain.Where
import io.embodit.puzzlebox.ui.board.input.parseLoggedPath
import io.embodit.puzzlebox.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * build.md「命中目标 → 格子必须有回放单测」: replay what the system reports as the hit node (a GLB mesh node under a block
 * holder, a pad marker, a pad, a tab face) through the template's name resolver (resolvePath), then into GameFlow through
 * the same [routeFlow] the volume uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private val up = listOf("root")
    private val box = listOf("boxRoot", "root")

    @Test fun everyHolderAndChildResolves() {
        for (i in 0..6) {
            assertEquals("pc:$i", BoardTargets.resolvePath(listOf("piece_L", BoardNames.piece(i)) + up))
            assertEquals("pc:$i", BoardTargets.resolvePath(listOf("primitive_0", "piece_T", BoardNames.piece(i)) + up))
        }
        for (x in 0..3) for (z in 0..3) {
            val n = BoardNames.pad(x, z)
            assertEquals(n, BoardTargets.resolvePath(listOf("mk:$n:0", n) + box))
        }
        for (t in Turn.entries) assertEquals(BoardNames.tab(t), BoardTargets.resolvePath(listOf("tabFace:${t.name}", BoardNames.tab(t)) + up))
        assertEquals("plate", BoardTargets.resolvePath(listOf("plate") + up))
    }

    @Test fun furnitureAndStrayNamesResolveToNothing() {
        for (n in listOf("boxModel", "acrylic:front", "mouthOverlay", "tray", "stand", "wedge", "lid", "hole:0", "ghost:1")) assertNull(n, BoardTargets.resolvePath(listOf(n) + box))
        assertNull(BoardTargets.resolvePath(listOf("pc:99") + up))
        assertNull(BoardTargets.resolvePath(listOf("pad:4:0") + box))
    }

    @Test fun replayedHitsDriveTheGame() = runTest {
        val f = FlowTest.flow(this, first = false); f.start(); f.chooseLevel(Levels.indexOf("W1")); advanceUntilIdle()
        fun go(path: String) { routeFlow(f, BoardTargets.resolvePath(parseLoggedPath(path))); advanceUntilIdle() }
        // logged path format: hit node first, then its parents
        go("piece_O < pc:0 < root")
        assertEquals(Where.STAND, f.state.value.pieces[0].where)
        assertEquals("stand", BoardTargets.squareOf("pc:0", f.state.value))
        go("tabFace:YAW < tab:yaw < root")
        go("mk:pad:0:0:0 < pad:0:0 < boxRoot < root")
        assertEquals(Where.BOX, f.state.value.pieces[0].where)
        assertEquals("mouth:1,1", BoardTargets.squareOf("pad:1:1", f.state.value))
        val st: PlayState = f.state.value
        assertEquals("box:" + st.pieces[0].cells.maxBy { it.y }.let { "${it.x},${it.z}" }, BoardTargets.squareOf("pc:0", st))
        assertEquals("-", BoardTargets.squareOf(resolvePath(listOf("boxModel"), BoardTargets::targetOf), st))
    }

    /** Real paths from a headset input-log go here after the first device round (none yet: headset not connected). */
    @Test fun replayHeadsetLogPaths() {
        val logged = listOf("pc:3 < root", "mk:pad:1:2:0 < pad:1:2 < boxRoot < root", "tab:pitch < root")
        for (p in logged) assertEquals(parseLoggedPath(p).first { BoardNames.hitOf(it) != BoardNames.NoHit }, BoardTargets.resolvePath(parseLoggedPath(p)))
    }
}
