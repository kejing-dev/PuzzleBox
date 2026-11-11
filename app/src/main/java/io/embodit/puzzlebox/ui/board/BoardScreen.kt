package io.embodit.puzzlebox.ui.board

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.puzzlebox.R
import io.embodit.puzzlebox.domain.GameFlow
import io.embodit.puzzlebox.domain.Layout
import io.embodit.puzzlebox.domain.Phase
import io.embodit.puzzlebox.domain.PlateFlash
import io.embodit.puzzlebox.domain.PlayState
import io.embodit.puzzlebox.domain.Turn
import io.embodit.puzzlebox.domain.V3
import io.embodit.puzzlebox.domain.Where
import io.embodit.puzzlebox.platform.Graph
import io.embodit.puzzlebox.platform.TAG
import io.embodit.puzzlebox.ui.board.input.BoardInputConfig
import io.embodit.puzzlebox.ui.board.input.BoardInputListener
import io.embodit.puzzlebox.ui.board.input.BoardInputLog
import io.embodit.puzzlebox.ui.board.input.InputSource
import io.embodit.puzzlebox.ui.board.input.Offset3M
import io.embodit.puzzlebox.ui.board.input.TargetResolver
import io.embodit.puzzlebox.ui.board.input.boardInput
import io.embodit.puzzlebox.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the volume's per-frame motion inside the ECS (never in Compose). */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { SceneHolder.scene?.onFrame(context.deltaTime) }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    @Volatile var testF = false
}

/** Plate line 2 shows for 2.5 s after an event (design standPlate, M15). */
const val FLASH_MS = 2500L

/**
 * The box volume (default container). Opens the control window beside it, shows the box, and routes every pinch /
 * trigger / drag on a holder (a block, a mouth pad, a tab, the S15 plate) to [GameFlow] through the factory board-input
 * template (D50: hand ray, controller ray and eye-hand alike).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { BoardScene(scope) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableLongStateOf(0L) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.open("start")
        onDispose { graph.window.detach() }
    }
    // the plate's clock and its 2.5 s second line
    LaunchedEffect(Unit) { while (true) { delay(500); tick = android.os.SystemClock.uptimeMillis() } }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // the app icon tapped again brings the volume to the front; a closed panel comes back beside it
                    if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    val loc = remember(context, settings.language) { context.localized(settings.language) }
    val texts = sceneTexts(state, graph.window.panelOpen, tick) { id, args -> loc.getString(id, *args) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .boardInput(remember { BoardTargets() }, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(dragStartM = 0.03f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底"), removed when the GLBs are in
                    val slab = MeshResource.createBox(Vector3(0.12f, 0.01f, 0.12f), cornerRadius = 0.003f)
                    // gate:allow geometry S00 loading stand-in tray (design lifecycle "首帧兜底"), removed when the GLBs are in
                    val tray = MeshResource.createBox(Vector3(0.62f, 0.015f, 0.24f), cornerRadius = 0.003f)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.89f, 0.78f, 0.6f, 1f)); setRoughness(0.8f) }
                    Triple(slab, tray, mat)
                }.getOrNull()
            }
            quick?.let { (s, t, m) -> scene.showFallback(s, t, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val models = Models { n -> Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) } }
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); delay(10_000) }
            runCatching { scene.install(resources, models) }.onFailure { Log.e(TAG, "MODELS failed", it) }
            Log.i(TAG, "MODELS ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, graph.window.panelOpen, texts)
        },
    )
}

/**
 * The plate lines (design spatialLayout.standPlate; D38: the same source as the panel) and the tab words, in the app
 * language. Line 1 "鞋盒 · 3 / 6 · 1:12" (full: "鞋盒 · 1:42"); line 2 = the panel-closed hint (S15) or a 2.5 s reason.
 */
internal fun sceneTexts(st: PlayState, panelOpen: Boolean, now: Long, s: (Int, Array<Any>) -> String): SceneTexts {
    val tabs = mapOf(Turn.YAW to s(R.string.tab_yaw, emptyArray()), Turn.PITCH to s(R.string.tab_pitch, emptyArray()), Turn.ROLL to s(R.string.tab_roll, emptyArray()))
    if (st.phase == Phase.LOADING || st.pieces.isEmpty()) return SceneTexts(tabs, "", null, SceneHolder.testF)
    val name = if (s(R.string.lang_code, emptyArray()) == "zh") st.level.nameZh else st.level.nameEn
    val t = clock(if (st.phase == Phase.SOLVED) st.elapsedMs else st.elapsed(now))
    val line1 = if (st.phase == Phase.SOLVED) s(R.string.plate_full, arrayOf(name, t)) else s(R.string.plate_line, arrayOf(name, st.placed, st.pieces.size, t))
    val flash = st.flash?.takeIf { now - st.flashAt in 0 until FLASH_MS || st.freezeMs != null }
    val line2 = when {
        !panelOpen -> s(R.string.plate_s15, emptyArray())
        flash != null -> s(when (flash) {
            PlateFlash.COACH -> R.string.plate_coach
            PlateFlash.TURN_HERE -> R.string.plate_turn_here
            PlateFlash.STICKS_OUT -> R.string.plate_sticks_out
            PlateFlash.TOO_BIG -> R.string.plate_too_big
            PlateFlash.HOLE -> R.string.plate_hole
            PlateFlash.TAKE_BLOCKED -> R.string.plate_blocked
        }, emptyArray())
        else -> null
    }
    // with a second line the first one is shorter: "鞋盒 · 3 / 6"
    val l1 = if (line2 != null && st.phase != Phase.SOLVED) s(R.string.plate_line_short, arrayOf(name, st.placed, st.pieces.size)) else line1
    return SceneTexts(tabs, l1, line2, SceneHolder.testF)
}

/** m:ss (or h:mm:ss past an hour). */
fun clock(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}

/** Hit entity (or an ancestor) -> the holder name the game knows; the box, tray, stand and wedge take no hits. */
internal class BoardTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { BoardNames.hitOf(it) != BoardNames.NoHit }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.puzzlebox.ui.board.input.resolvePath(path, ::targetOf)

        /** The square a target stands for (input-log `square=`): a block's place, a mouth cell, a tab, the plate, or "-". */
        fun squareOf(target: String?, st: PlayState): String = when (val h = BoardNames.hitOf(target)) {
            is BoardNames.PieceHit -> st.pieces.getOrNull(h.index)?.let { p ->
                when (p.where) { Where.BOX -> "box:" + p.cells.maxBy { it.y }.let { "${it.x},${it.z}" }; Where.STAND -> "stand"; Where.TRAY -> "tray:${h.index}" }
            } ?: "-"
            is BoardNames.PadHit -> "mouth:${h.x},${h.z}"
            is BoardNames.TabHit -> "tab:${h.turn.name.lowercase()}"
            BoardNames.PlateHit -> "plate"
            BoardNames.NoHit -> "-"
        }
    }
}

/** Every resolved tap / drag goes through [routeTap] / [GameFlow] (the same calls the panel and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    private var dragging = false

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value)}")
        routeTap(graph, target)
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value)}")
        val h = BoardNames.hitOf(target) as? BoardNames.PieceHit ?: run { routeTap(graph, target); return true }
        dragging = graph.flow.dragStart(h.index)
        if (!dragging) routeTap(graph, target)
        return true
    }

    override fun onDragMove(totalM: Offset3M) { if (dragging) graph.flow.dragMove(V3(totalM.x, totalM.y, totalM.z)) }

    override fun onDragEnd() { if (dragging) graph.flow.dragEnd(); dragging = false }

    override fun onDragCancel() { if (dragging) graph.flow.dragCancel(); dragging = false }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** Holder name -> the one flow call (the S15 plate reopens the panel). */
internal fun routeTap(graph: Graph, name: String?) {
    if (BoardNames.hitOf(name) == BoardNames.PlateHit) { Log.i(TAG, "S15 plate pinch -> reopen panel"); graph.window.open("plate"); return }
    routeFlow(graph.flow, name)
}

/** [routeTap] without the Android graph (hit-replay unit tests drive a bare GameFlow through it). */
internal fun routeFlow(flow: GameFlow, name: String?) {
    when (val h = BoardNames.hitOf(name)) {
        is BoardNames.PieceHit -> flow.tapPiece(h.index)
        is BoardNames.PadHit -> flow.tapPad(h.x, h.z)
        is BoardNames.TabHit -> flow.turn(h.turn)
        BoardNames.PlateHit, BoardNames.NoHit -> Unit
    }
}

@Suppress("unused")
private val unusedLayout = Layout.U
