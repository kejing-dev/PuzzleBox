package io.embodit.puzzlebox.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The box volume is the default container (manifest). */
object Containers {
    const val BOARD = "PuzzleBoxVolume"
    const val CONTROL = "PuzzleBoxControl"
    const val CONTROL_TAG = "control"
}

/** The four pages of the control window (design panels.control: 玩 / 盒子 / 玩法 / 设置). */
enum class Page(val stateId: String) { PLAY("S02"), BOXES("S11"), RULES("S12"), SETTINGS("S13") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
    var clearConfirm by mutableStateOf(false)
    /** The group shown on the Boxes page (0 热身 / 1 进阶 / 2 索玛). */
    var group by mutableStateOf(-1)
}
