package io.embodit.puzzlebox.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.puzzlebox.BuildConfig
import io.embodit.puzzlebox.R
import io.embodit.puzzlebox.domain.Confirm
import io.embodit.puzzlebox.domain.Language
import io.embodit.puzzlebox.domain.Level
import io.embodit.puzzlebox.domain.Levels
import io.embodit.puzzlebox.domain.Notice
import io.embodit.puzzlebox.domain.Phase
import io.embodit.puzzlebox.domain.PlayState
import io.embodit.puzzlebox.domain.Records
import io.embodit.puzzlebox.domain.Settings
import io.embodit.puzzlebox.domain.Where
import io.embodit.puzzlebox.platform.Graph
import io.embodit.puzzlebox.platform.TAG
import io.embodit.puzzlebox.ui.Page
import io.embodit.puzzlebox.ui.Ui
import io.embodit.puzzlebox.ui.board.clock
import io.embodit.puzzlebox.ui.theme.PuzzleColors
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the box, 600 x 520 dp): all 2D UI, four pages switched by the native
 * SpatialUI TabBar (D8): 玩 / 盒子 / 玩法 / 设置. No page scrolls (D44); every page spreads its content between a header and
 * a last row (SpaceBetween) so the blank outside the content is only the window padding.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val records by graph.flow.records.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val sceneReady by graph.sceneReady.collectAsStateWithLifecycle()
    // S01: the rules page opens first, once the scene is built (S00 shows the play page's loading line first, QA K4);
    // [开始装] (or the first touch on a block) ends it
    LaunchedEffect(state.welcome, sceneReady) {
        if (state.welcome && sceneReady) Ui.page = Page.RULES
        else if (state.welcome) Ui.page = Page.PLAY
        else if (Ui.page == Page.RULES && state.coach) Ui.page = Page.PLAY
    }
    LaunchedEffect(state.phase) { if (state.phase == Phase.SOLVED) Ui.page = Page.PLAY }
    LaunchedEffect(state.confirm) { if (state.confirm is Confirm.Restart) Ui.page = Page.PLAY }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) { Ui.exitConfirm = false; Ui.clearConfirm = false }
        if (Ui.page != Page.BOXES && graph.flow.state.value.confirm is Confirm.Switch) graph.flow.cancelConfirm()
    }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = SystemClock.uptimeMillis() } }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, records, graph, now, sceneReady)
            Page.BOXES -> BoxesPage(state, records, graph)
            Page.RULES -> RulesPage(state, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass plain
    // strings in (ui_gates tabbar-lang; an English cold start shows four English tabs).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.BOXES, R.drawable.ic_tab_boxes, stringResource(R.string.tab_boxes)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

@Composable
private fun Header(title: String, tag: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        if (sub != null) {
            Spacer(Modifier.width(12.dp))
            Txt(sub, "$tag/slogan", style = PicoTheme.typography.bodyMedium, color = PuzzleColors.InkSecondary, modifier = Modifier.weight(1f), maxLines = 2)
        }
    }
}

@Composable
internal fun moves(n: Int): String = LocalContext.current.resources.getQuantityString(R.plurals.moves, n, n)

@Composable
internal fun isZh(): Boolean = stringResource(R.string.lang_code) == "zh"

@Composable
internal fun levelName(l: Level): String = if (isZh()) l.nameZh else l.nameEn

@Composable
internal fun levelSlogan(l: Level): String = if (isZh()) l.sloganZh else l.sloganEn

/** The status sentence (design content.strings.status): one line that says what you can do next. */
@Composable
internal fun statusText(st: PlayState): String = when {
    st.phase == Phase.LOADING -> stringResource(R.string.status_loading)
    st.notice == Notice.HOLE -> stringResource(R.string.status_hole)
    st.notice == Notice.STUCK || (st.stuck && st.hintOn) -> stringResource(R.string.status_stuck)
    st.notice == Notice.STICKS_OUT -> stringResource(R.string.status_sticks_out)
    st.notice == Notice.TOO_BIG -> stringResource(R.string.status_too_big)
    st.notice == Notice.THINKING -> stringResource(R.string.status_thinking)
    st.notice == Notice.HELP_HERE -> stringResource(R.string.status_help_here)
    st.notice == Notice.HELP_NONE -> stringResource(R.string.status_help_none)
    st.notice == Notice.TAKEN -> stringResource(R.string.status_taken)
    st.notice == Notice.TAKE_BLOCKED -> stringResource(R.string.status_blocked)
    st.notice == Notice.PICK_FIRST -> stringResource(R.string.status_pick_first)
    st.notice == Notice.RESTORED && st.restored -> stringResource(R.string.status_restored, levelName(st.level))
    st.coach || st.notice == Notice.COACH -> stringResource(R.string.status_coach)
    st.selected != null && st.pieces[st.selected].where == Where.STAND -> stringResource(R.string.status_selected)
    else -> stringResource(R.string.status_idle)
}

// ------------------------------------------------------------------ Play

@Composable
private fun PlayPage(st: PlayState, settings: Settings, records: Records, graph: Graph, now: Long, sceneReady: Boolean) {
    val flow = graph.flow
    if (st.phase == Phase.LOADING || st.pieces.isEmpty() || !sceneReady) {
        PageColumn {
            Header(stringResource(R.string.app_name), "play")
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Txt(stringResource(R.string.status_loading), "play/loading", style = PicoTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = {}, enabled = false, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_restart), "play/restart", onClick = {}, enabled = false, icon = R.drawable.ic_restart, modifier = Modifier.weight(1f))
            }
        }
        return
    }
    if (st.phase == Phase.SOLVED && st.result != null) { ResultPage(st, settings, graph); return }
    val best = records.of(st.level.id).bestMs?.let { clock(it) } ?: stringResource(R.string.best_none)
    val busy = st.busy(now)
    val holeOrStuck = st.notice == Notice.HOLE || st.notice == Notice.STUCK || st.stuck
    PageColumn {
        Header(levelName(st.level), "play", levelSlogan(st.level))
        Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(24.dp)).background(PuzzleColors.TagFace).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Txt(statusText(st), "play/status", style = PicoTheme.typography.titleMedium, color = PuzzleColors.Ink, maxLines = 3)
        }
        Txt(stringResource(R.string.readout, st.placed, st.pieces.size, clock(st.elapsed(now)), moves(st.moves), best), "play/readout",
            style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = st.canUndo && !busy, icon = R.drawable.ic_undo,
                highlight = holeOrStuck && st.canUndo, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(if (st.hintOn) R.string.btn_hint_on else R.string.btn_hint_off), "play/hint", on = st.hintOn, icon = R.drawable.ic_hint,
                onClick = { flow.setHint(!st.hintOn) }, enabled = st.confirm == null, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_help), "play/help", onClick = { flow.help() }, enabled = !busy && st.confirm == null && st.notice != Notice.THINKING,
                icon = R.drawable.ic_help, modifier = Modifier.weight(1.25f))
        }
        if (st.confirm is Confirm.Restart) {
            // S14: the confirmation unfolds in place (design panels.control.play)
            Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(20.dp)).background(PuzzleColors.TagFace).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(R.string.confirm_restart), "play/confirm", style = PicoTheme.typography.bodyLarge, color = PuzzleColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_cancel), "play/confirm-no", onClick = { flow.cancelConfirm() }, modifier = Modifier.width(128.dp))
                SecondaryButton(stringResource(R.string.btn_confirm_restart), "play/confirm-yes", danger = true, onClick = { flow.confirm() }, modifier = Modifier.width(112.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_unmute), "play/mute", on = !settings.sound,
                    icon = if (settings.sound) R.drawable.ic_mute else R.drawable.ic_sound,
                    onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_restart), "play/restart", onClick = { flow.requestRestart() }, icon = R.drawable.ic_restart,
                    enabled = !busy, modifier = Modifier.weight(1f))
            }
        }
        Txt(stringResource(R.string.rules_anchor), "play/anchor", style = PicoTheme.typography.bodyMedium, color = PuzzleColors.InkSecondary, maxLines = 2)
    }
}

/** S10: the result card (whole page). Undo / hints / help are put away while the box is full. */
@Composable
private fun ResultPage(st: PlayState, settings: Settings, graph: Graph) {
    val r = st.result ?: return
    PageColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Header(levelName(st.level), "result", levelSlogan(st.level)) }
            ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_unmute), "result/mute", on = !settings.sound,
                icon = if (settings.sound) R.drawable.ic_mute else R.drawable.ic_sound,
                onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = Modifier.width(140.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp).clip(RoundedCornerShape(28.dp)).background(PuzzleColors.TagFace).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceEvenly) {
            Txt(stringResource(R.string.result_title, clock(r.ms), moves(r.moves)), "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold), color = PuzzleColors.Ink, maxLines = 2)
            if (r.somaFound != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.result_soma, r.somaFound), "result/soma", style = PicoTheme.typography.titleMedium, color = PuzzleColors.Ink)
                    if (r.somaNew) { Spacer(Modifier.width(10.dp)); Chip(stringResource(R.string.result_soma_new), "result/soma-new") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.counted) {
                    Txt(stringResource(R.string.result_best, r.bestMs?.let { clock(it) } ?: clock(r.ms)), "result/best", style = PicoTheme.typography.bodyLarge, color = PuzzleColors.Ink)
                    if (r.newBest) { Spacer(Modifier.width(10.dp)); Chip(stringResource(R.string.result_record), "result/record") }
                } else Txt(stringResource(R.string.result_uncounted), "result/uncounted", style = PicoTheme.typography.bodyMedium, color = PuzzleColors.InkSecondary, maxLines = 2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.btn_next), "result/next", onClick = { graph.flow.next() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f).height(56.dp))
            }
        }
        Txt(stringResource(R.string.result_rest) + " " + stringResource(if (st.level.soma) R.string.rules_source else R.string.result_rule), "result/rest",
            style = PicoTheme.typography.bodySmall, color = PuzzleColors.InkSecondary, maxLines = 3)
    }
}

@Composable
private fun Chip(text: String, tag: String) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(PuzzleColors.Primary).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, tag, style = PicoTheme.typography.labelLarge, color = PuzzleColors.OnPrimary)
    }
}

// ------------------------------------------------------------------ Boxes

@Composable
private fun BoxesPage(st: PlayState, records: Records, graph: Graph) {
    val group = if (Ui.group in 0..2) Ui.group else st.level.group
    val groups = listOf(0 to stringResource(R.string.group_0), 1 to stringResource(R.string.group_1), 2 to stringResource(R.string.group_2))
    PageColumn {
        Header(stringResource(R.string.boxes_title), "boxes")
        // gate:allow tabs-segment the Boxes page shows one of its three groups (same-page option, design panels.control.boxes), pages switch with the native TabBar
        Segments(groups, group, "boxes/group", { Ui.group = it }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Levels.ALL.withIndex().filter { it.value.group == group }.forEach { (i, l) -> BoxCard(i, l, st, records, graph, Modifier.weight(1f)) }
        }
        val c = st.confirm
        if (c is Confirm.Switch) {
            Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp)).background(PuzzleColors.TagFace).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(R.string.confirm_switch), "boxes/confirm", style = PicoTheme.typography.bodyMedium, color = PuzzleColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_cancel), "boxes/confirm-no", onClick = { graph.flow.cancelConfirm() }, modifier = Modifier.width(128.dp))
                SecondaryButton(stringResource(R.string.btn_switch_yes), "boxes/confirm-yes", danger = true, onClick = { graph.flow.confirm(); Ui.page = Page.PLAY }, modifier = Modifier.width(112.dp))
            }
        } else {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.CenterStart) {
                Txt(stringResource(R.string.boxes_note), "boxes/note", style = PicoTheme.typography.bodyMedium, color = PuzzleColors.InkSecondary, maxLines = 2)
            }
        }
    }
}

@Composable
private fun BoxCard(index: Int, l: Level, st: PlayState, records: Records, graph: Graph, modifier: Modifier) {
    val rec = records.of(l.id)
    val current = index == st.levelIndex
    Column(
        modifier.height(236.dp).clip(RoundedCornerShape(18.dp)).background(PuzzleColors.CardFace)
            .border(if (current) 3.dp else 0.dp, if (current) PuzzleColors.Primary else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable { Log.i(TAG, "BOX card ${l.id}"); graph.flow.chooseLevel(index); if (graph.flow.state.value.confirm == null) Ui.page = Page.PLAY }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
    ) {
        BoxThumb(l, Modifier.size(84.dp))
        Txt(levelName(l), "boxes/name", style = PicoTheme.typography.labelLarge, color = PuzzleColors.Ink, maxLines = 2, align = TextAlign.Center)
        Txt(stringResource(R.string.box_blocks, l.pieces.size) + " · ${l.dims.x}×${l.dims.y}×${l.dims.z}", "boxes/size", style = PicoTheme.typography.bodySmall, color = PuzzleColors.InkSecondary, align = TextAlign.Center, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { k -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (k < l.difficulty) PuzzleColors.Primary else PuzzleColors.InkSecondary.copy(alpha = 0.25f))) }
        }
        val line = when {
            current && st.progress && st.phase == Phase.PLAY -> stringResource(R.string.box_current)
            rec.done -> rec.bestMs?.let { stringResource(R.string.box_best, clock(it)) } ?: stringResource(R.string.box_done)
            else -> stringResource(R.string.box_not_yet)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rec.done) { Icon(painter = painterResource(R.drawable.ic_done), contentDescription = stringResource(R.string.box_done), modifier = Modifier.size(16.dp), tint = PuzzleColors.Done); Spacer(Modifier.width(3.dp)) }
            Txt(line, "boxes/best", style = PicoTheme.typography.bodySmall, color = PuzzleColors.Ink, maxLines = 2, align = TextAlign.Center)
        }
    }
}

/** Isometric thumbnail of a box: the X x Y x Z cell grid outline with a few coloured blocks inside (88 dp, design boxes). */
@Composable
private fun BoxThumb(l: Level, modifier: Modifier) {
    Canvas(modifier) {
        val d = l.dims
        val k = size.minDimension / 6.2f
        val ox = size.width / 2; val oy = size.height * 0.80f
        fun pt(x: Float, y: Float, z: Float) = Offset(ox + (x - z) * k * 0.87f - (d.x - d.z) * k * 0.87f / 2, oy - y * k - (x + z) * k * 0.5f + (d.x + d.z) * k * 0.25f - d.y * k * 0.15f)
        // the solution's blocks as small cubes, back to front
        val cells = l.solution.flatMap { s -> s.cells.map { it to Color(s.piece.color) } }.filter { it.first.y < d.y - 1 || d.y == 1 }
        for ((c, col) in cells.sortedWith(compareBy({ it.first.y }, { -(it.first.x + it.first.z) }))) cube(c.x.toFloat(), c.y.toFloat(), c.z.toFloat(), col, ::pt)
        // box frame
        val frame = PuzzleColors.Walnut
        val st = Stroke(width = 2.dp.toPx())
        fun edge(a: Offset, b: Offset) = drawLine(frame, a, b, strokeWidth = st.width)
        val X = d.x.toFloat(); val Y = d.y.toFloat(); val Z = d.z.toFloat()
        for ((a, b) in listOf(
            pt(0f, 0f, 0f) to pt(X, 0f, 0f), pt(X, 0f, 0f) to pt(X, 0f, Z), pt(X, 0f, Z) to pt(0f, 0f, Z), pt(0f, 0f, Z) to pt(0f, 0f, 0f),
            pt(0f, Y, 0f) to pt(X, Y, 0f), pt(X, Y, 0f) to pt(X, Y, Z), pt(X, Y, Z) to pt(0f, Y, Z), pt(0f, Y, Z) to pt(0f, Y, 0f),
            pt(0f, 0f, 0f) to pt(0f, Y, 0f), pt(X, 0f, 0f) to pt(X, Y, 0f), pt(X, 0f, Z) to pt(X, Y, Z), pt(0f, 0f, Z) to pt(0f, Y, Z),
        )) edge(a, b)
    }
}

private fun DrawScope.cube(x: Float, y: Float, z: Float, c: Color, pt: (Float, Float, Float) -> Offset) {
    fun face(a: Offset, b: Offset, c2: Offset, d: Offset, shade: Float) {
        val p = listOf(a, b, c2, d)
        val path = Path().apply { moveTo(p[0].x, p[0].y); p.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(path, Color(c.red * shade, c.green * shade, c.blue * shade, 1f))
    }
    face(pt(x, y + 1, z), pt(x + 1, y + 1, z), pt(x + 1, y + 1, z + 1), pt(x, y + 1, z + 1), shade = 1f)
    face(pt(x + 1, y, z), pt(x + 1, y + 1, z), pt(x + 1, y + 1, z + 1), pt(x + 1, y, z + 1), shade = 0.8f)
    face(pt(x, y, z + 1), pt(x + 1, y, z + 1), pt(x + 1, y + 1, z + 1), pt(x, y + 1, z + 1), shade = 0.65f)
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(st: PlayState, graph: Graph) {
    PageColumn {
        Header(stringResource(if (st.welcome) R.string.welcome_title else R.string.rules_title), "rules")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RulesFigure(Modifier.size(width = 150.dp, height = 150.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(PuzzleColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = PuzzleColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.weight(1f))
                    }
                }
                Txt(stringResource(R.string.rules_anchor), "rules/anchor", style = PicoTheme.typography.bodyMedium, color = PuzzleColors.InkSecondary, maxLines = 2)
            }
        }
        Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3).forEach { id ->
                Box(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(14.dp)).background(PuzzleColors.TagFace).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = PuzzleColors.Ink, maxLines = 4)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall, color = PuzzleColors.InkSecondary, maxLines = 2)
                Txt(stringResource(R.string.rules_about, BuildConfig.VERSION_NAME), "rules/about", style = PicoTheme.typography.bodySmall, color = PuzzleColors.InkSecondary, maxLines = 2)
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton(stringResource(if (st.welcome) R.string.btn_start else R.string.btn_back_play), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY })
        }
    }
}

/** The rules picture: the tilted box, the three tabs and one block dropping in (design rulesPage "一张图"). */
@Composable
private fun RulesFigure(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(PuzzleColors.TagFace)) {
        val w = size.width; val h = size.height
        // wedge + box (side view, tilted)
        val walnut = PuzzleColors.Walnut
        val base = Path().apply { moveTo(w * 0.10f, h * 0.88f); lineTo(w * 0.62f, h * 0.88f); lineTo(w * 0.62f, h * 0.80f); lineTo(w * 0.10f, h * 0.62f); close() }
        drawPath(base, walnut.copy(alpha = 0.85f))
        val box = Path().apply { moveTo(w * 0.10f, h * 0.62f); lineTo(w * 0.62f, h * 0.80f); lineTo(w * 0.72f, h * 0.50f); lineTo(w * 0.20f, h * 0.32f); close() }
        drawPath(box, PuzzleColors.Acrylic.copy(alpha = 0.9f))
        drawPath(box, walnut, style = Stroke(width = 3.dp.toPx()))
        // blocks inside
        drawRect(Color(0xFF4D7CC7), Offset(w * 0.22f, h * 0.55f), androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.12f))
        drawRect(Color(0xFF8AB17D), Offset(w * 0.40f, h * 0.60f), androidx.compose.ui.geometry.Size(w * 0.14f, h * 0.10f))
        // a red T falling in, with a motion line
        drawRect(Color(0xFFD1495B), Offset(w * 0.36f, h * 0.10f), androidx.compose.ui.geometry.Size(w * 0.20f, h * 0.10f))
        drawRect(Color(0xFFD1495B), Offset(w * 0.42f, h * 0.20f), androidx.compose.ui.geometry.Size(w * 0.08f, h * 0.07f))
        drawLine(walnut, Offset(w * 0.46f, h * 0.29f), Offset(w * 0.47f, h * 0.40f), strokeWidth = 2.dp.toPx())
        // the three tabs at the right
        for (k in 0 until 3) drawRect(PuzzleColors.Maple, Offset(w * 0.70f + k * w * 0.095f, h * 0.84f), androidx.compose.ui.geometry.Size(w * 0.08f, h * 0.10f))
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    if (Ui.exitConfirm) {
        ConfirmPage(stringResource(R.string.confirm_exit), stringResource(R.string.confirm_exit_line), stringResource(R.string.btn_exit_yes),
            R.drawable.ic_exit, stringResource(R.string.settings_title), "exit", onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false })
        return
    }
    if (Ui.clearConfirm) {
        ConfirmPage(stringResource(R.string.confirm_clear), stringResource(R.string.confirm_clear_line), stringResource(R.string.btn_clear_yes),
            R.drawable.ic_restart, stringResource(R.string.settings_title), "clear", onYes = { graph.flow.clearRecords(); Ui.clearConfirm = false }, onNo = { Ui.clearConfirm = false })
        return
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Header(stringResource(R.string.settings_title), "settings")
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 120.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 120.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", labelWidth = 120.dp) {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 120.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.btn_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.width(200.dp))
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.settings_reopen_note), "settings/reopen-note", style = PicoTheme.typography.bodySmall, color = PuzzleColors.InkSecondary, maxLines = 3, modifier = Modifier.weight(1f))
        }
        // D26: quit is on the last row; both confirmations are whole-page cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.btn_clear), "settings/clear", onClick = { Ui.clearConfirm = true }, icon = R.drawable.ic_restart, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT") }, icon = R.drawable.ic_exit, modifier = Modifier.weight(1f), danger = true)
        }
    }
}

@Composable
private fun ConfirmPage(q: String, line: String, yes: String, icon: Int, title: String, tag: String, onYes: () -> Unit, onNo: () -> Unit) {
    PageColumn {
        Header(title, "confirm")
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(PuzzleColors.TagFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = PuzzleColors.Ink)
            Txt(q, "confirm/$tag", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = PuzzleColors.Ink, maxLines = 2)
            Txt(line, "confirm/line", style = PicoTheme.typography.bodyLarge, color = PuzzleColors.InkSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(72.dp))
                SecondaryButton(yes, "confirm/yes", danger = true, onClick = onYes, modifier = Modifier.weight(1f).height(72.dp))
            }
        }
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                // QA K6 / B19: the capsule only draws mainContent (supportContent never shows, pico-cli-feedback), so the
                // word goes under the icon inside mainContent
                mainContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                        Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelSmall, maxLines = 1)
                    }
                },
            )
        }
    }
}
