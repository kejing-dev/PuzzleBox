package io.embodit.puzzlebox

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.puzzlebox.ui.Containers
import io.embodit.puzzlebox.ui.board.BoardScreen
import io.embodit.puzzlebox.ui.control.ControlWindow
import io.embodit.puzzlebox.ui.theme.PuzzleTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the box volume (default container,
 * 0.66 x 0.40 x 0.56 m from the manifest) and the control window (Planar 600 x 520 dp, min 560 x 480, max 900 x 640) to
 * its right. Every open (first open, the plate pinch in S15, [重开控制窗]) uses Placement.right(box) (profile D44
 * correction: Shared Space never promises "in front of you"; the user moves the panel with the system handle).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PuzzleTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 600.dp, height = 520.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            PuzzleTheme {
                Box(Modifier.windowConstraints(minWidth = 560.dp, minHeight = 480.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
