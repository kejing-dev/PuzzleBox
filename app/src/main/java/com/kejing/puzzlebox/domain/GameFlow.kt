package com.kejing.puzzlebox.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Phase { LOADING, PLAY, SOLVED }

/** The status sentence (design content.strings.status); NONE = the phase's default sentence. */
enum class Notice { NONE, COACH, PICK_FIRST, STICKS_OUT, TOO_BIG, HOLE, STUCK, THINKING, HELP_HERE, HELP_NONE, TAKEN, TAKE_BLOCKED, RESTORED }

/** The name plate's temporary second line (design spatialLayout.standPlate, 2.5 s, M15). */
enum class PlateFlash { COACH, TURN_HERE, STICKS_OUT, TOO_BIG, HOLE, TAKE_BLOCKED }

sealed interface Confirm {
    data object Restart : Confirm
    data class Switch(val level: Int) : Confirm
}

enum class Sound { TURN, DROP, REJECT, HOLE, TAKE, LID, SOLVED }

/** The block held in a drag (design input.drag): its column (hysteresis), what would happen, where it is drawn. */
data class DragView(val piece: Int, val base: Pose, val column: Pair<Int, Int>?, val result: DropResult?, val pose: Pose)

data class Result(val ms: Long, val moves: Int, val counted: Boolean, val newBest: Boolean, val bestMs: Long?, val somaFound: Int?, val somaNew: Boolean)

/** Everything the volume and the control window draw. Immutable; [GameFlow] replaces it on every change. */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val levelIndex: Int = 0,
    val pieces: List<PieceState> = emptyList(),
    val selected: Int? = null,
    val moves: Int = 0,
    val elapsedMs: Long = 0,
    val runningSince: Long? = null,
    val usedHint: Boolean = false,
    val usedHelp: Boolean = false,
    val undo: List<UndoEntry> = emptyList(),
    val motions: Map<Int, Motion> = emptyMap(),
    val notice: Notice = Notice.NONE,
    val flash: PlateFlash? = null,
    val flashAt: Long = 0,
    val hintOn: Boolean = false,
    val hint: Placement? = null,
    val hintPiece: Int? = null,
    val stuck: Boolean = false,
    val drag: DragView? = null,
    val solvedAt: Long = 0,
    val result: Result? = null,
    val welcome: Boolean = false,
    val coach: Boolean = false,
    val confirm: Confirm? = null,
    val freezeMs: Long? = null,
    val restored: Boolean = false,
    val levelAt: Long = 0,
    /** A block in the box that was tapped but is pinned (S17 blocked): (piece, at). */
    val pinned: Pair<Int, Long>? = null,
    val reduced: Boolean = false,
) {
    val level: Level get() = Levels.ALL[levelIndex]
    val dims: Dims get() = level.dims
    val placed: Int get() = pieces.count { it.where == Where.BOX }
    val tray: Layout.Tray get() = trayOf(levelIndex)

    fun grid(): BoxGrid = BoxGrid(dims).also { g -> pieces.forEachIndexed { i, p -> if (p.where == Where.BOX) g.put(i, p.cells) } }
    fun holes(): List<Cell> = grid().holes()
    fun elapsed(now: Long): Long = elapsedMs + (runningSince?.let { now - it } ?: 0L)
    val canUndo: Boolean get() = phase == Phase.PLAY && undo.isNotEmpty() && confirm == null
    val progress: Boolean get() = moves > 0 || placed > 0

    /** Elapsed ms of a motion, clamped by a QA hold. */
    fun motionElapsed(m: Motion, now: Long): Long = (now - m.startedAt).let { e -> freezeMs?.let { minOf(e, it) } ?: e }

    /** Where block [i] is drawn right now (motion, drag, or its rest pose). */
    fun poseOf(i: Int, now: Long): Pose {
        drag?.takeIf { it.piece == i }?.let { return it.pose }
        motions[i]?.let { m -> if (moving(m, now)) return m.at(motionElapsed(m, now)) }
        return restPose(i)
    }

    fun restPose(i: Int): Pose {
        val p = pieces[i]
        return when (p.where) {
            Where.TRAY -> Layout.inTray(p.type, tray.slots[i])
            Where.STAND -> Layout.onStand(p.type, p.rot)
            Where.BOX -> Layout.inBox(dims, p.type, p.rot, Cell(p.cells.minOf { it.x }, p.cells.minOf { it.y }, p.cells.minOf { it.z }))
        }
    }

    /** A motion still playing (a QA hold freezes it mid-way: then it stays "playing" until the hold is released). */
    fun moving(m: Motion, now: Long): Boolean = motionElapsed(m, now) < m.totalMs

    fun busy(now: Long): Boolean = motions.values.any { m -> m.segs.any { it.kind != Seg.Kind.TURN } && moving(m, now) }

    companion object {
        private val trays = HashMap<Int, Layout.Tray>()
        fun trayOf(index: Int): Layout.Tray = synchronized(trays) { trays.getOrPut(index) { Layout.tray(Levels.ALL[index].pieces) } }
    }
}

interface RecordStore {
    fun load(): Records
    fun save(r: Records)
}

/**
 * The single game flow (design states S00..S17). The volume (BoardInput), the control window and the debug broadcast
 * all call these same functions (D29: debug commands take the user's path). Long work (the solver) runs on [engine];
 * everything else on the main thread.
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engine: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val recordStore: RecordStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val onSound: (Sound, Float) -> Unit,
    private val log: (String) -> Unit,
) {
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val _records = MutableStateFlow(Records())
    val records: StateFlow<Records> = _records.asStateFlow()

    /** Bumped on every change of the box contents; stale solver results and sounds check it. */
    private var gen = 0L
    private var solverJob: Job? = null
    private var autoJob: Job? = null
    private var foreground = true

    private val st: PlayState get() = _state.value
    private fun set(s: PlayState) { _state.value = s }
    private fun now() = clock()

    // ------------------------------------------------------------------ start / levels

    fun start() {
        _records.value = runCatching { recordStore.load() }.getOrDefault(Records())
        val saved = runCatching { store.load() }.getOrNull()
        val s = settings()
        val reduced = s.reducedMotion
        if (saved != null && s.firstRunDone) {
            val idx = Levels.indexOf(saved.levelId)
            val fresh = PlayState(
                phase = Phase.PLAY, levelIndex = idx, pieces = saved.pieces, selected = saved.selected, moves = saved.moves,
                elapsedMs = saved.elapsedMs, usedHint = saved.usedHint, usedHelp = saved.usedHelp, hintOn = saved.hintOn, undo = saved.undo,
                notice = Notice.RESTORED, restored = true, levelAt = now(), reduced = reduced,
            )
            set(fresh)
            log("RESTORE S16 level=${saved.levelId} placed=${fresh.placed} moves=${saved.moves}")
            if (fresh.grid().full) solvedNow(fresh, now(), restoring = true)
            refreshSolver()
        } else {
            load(0, welcome = !s.firstRunDone)
            log("START ${if (!s.firstRunDone) "S01 first run" else "S02"}")
        }
    }

    private fun load(index: Int, welcome: Boolean = false) {
        gen++
        solverJob?.cancel()
        val lv = Levels.ALL[index]
        set(PlayState(
            phase = Phase.PLAY, levelIndex = index, pieces = lv.pieces.map { PieceState(it, Where.TRAY, it.flatRot) },
            hintOn = st.hintOn, usedHint = st.hintOn, welcome = welcome, coach = welcome, levelAt = now(), reduced = settings().reducedMotion,
            notice = if (welcome) Notice.COACH else Notice.NONE,
        ))
        persist()
        refreshSolver()
        log("LEVEL ${lv.id} ${lv.dims} pieces=${lv.pieces}")
    }

    fun welcomeDone() {
        if (!st.welcome) return
        set(st.copy(welcome = false, flash = PlateFlash.COACH, flashAt = now()))
        onSettings(settings().copy(firstRunDone = true))
        log("WELCOME done -> S02 coach")
    }

    /** The block the coach points at (S01): the leftmost tray block. */
    fun coachPiece(s: PlayState = st): Int? = s.pieces.indices.filter { s.pieces[it].where == Where.TRAY }.minByOrNull { i -> s.tray.slots[i].let { it.x0 * 10 + it.row } }

    // ------------------------------------------------------------------ helpers

    private fun persist() {
        val s = st
        if (s.phase == Phase.LOADING) return
        runCatching {
            store.save(SavedGame(s.level.id, s.pieces, s.selected, s.moves, s.elapsed(now()), s.usedHint, s.usedHelp, s.hintOn, s.undo.takeLast(50)))
        }.onFailure { log("SAVE failed $it") }
    }

    private fun startClock(s: PlayState): PlayState = if (s.runningSince == null && s.phase == Phase.PLAY && foreground) s.copy(runningSince = now()) else s

    private fun pruneMotions(s: PlayState, t: Long): Map<Int, Motion> = if (s.freezeMs != null) s.motions else s.motions.filterValues { !it.done(t) }

    private fun withMotion(s: PlayState, piece: Int, segs: List<Seg>, t: Long): PlayState =
        s.copy(motions = pruneMotions(s, t) + (piece to Motion(segs, t)))

    private fun fly(from: Pose, to: Pose, ms: Long, arc: Float = 0f) = Seg(from, to, ms, Seg.Kind.FLY, arc = if (st.reduced) 0f else arc)

    private fun later(ms: Long, block: () -> Unit) {
        val g = gen
        scope.launch { delay(ms); if (g == gen) block() }
    }

    private fun flash(s: PlayState, f: PlateFlash) = s.copy(flash = f, flashAt = now())

    private fun inputOk(): Boolean = st.phase == Phase.PLAY && st.confirm == null && !st.busy(now())

    /** Send the selected block (if any) back to the tray (flat), with its flight. */
    private fun deselect(s: PlayState, t: Long): PlayState {
        val i = s.selected ?: return s
        val p = s.pieces[i]
        val from = s.poseOf(i, t)
        val pieces = s.pieces.toMutableList().also { it[i] = p.copy(where = Where.TRAY, rot = p.type.flatRot, cells = emptyList()) }
        val n = s.copy(pieces = pieces, selected = null, drag = null)
        return withMotion(n, i, listOf(fly(from, n.restPose(i), 250, 0.02f)), t)
    }

    // ------------------------------------------------------------------ taps (volume)

    /** A pinch / trigger on block [i] (tray, stand or box). */
    fun tapPiece(i: Int) {
        val s = st
        if (i !in s.pieces.indices) return
        if (s.phase == Phase.SOLVED) return
        if (!inputOk()) { log("TAP piece $i ignored (busy)"); return }
        val t = now()
        val p = s.pieces[i]
        when (p.where) {
            Where.TRAY -> select(i)
            Where.STAND -> {
                // back to the tray, flat again (T20)
                set(deselect(s, t).copy(notice = Notice.NONE))
                log("BACK piece $i -> tray S02")
                persist()
            }
            Where.BOX -> {
                if (s.selected != null) {
                    // a selected block is aimed at the column of the cell that was hit (the mouth pads sit above it)
                    val c = p.cells.maxByOrNull { it.y }!!
                    tapPad(c.x, c.z)
                } else takeOut(i)
            }
        }
    }

    private fun select(i: Int) {
        var s = st
        val t = now()
        if (s.selected != null && s.selected != i) s = deselect(s, t)
        val p = s.pieces[i]
        val from = s.poseOf(i, t)
        val pieces = s.pieces.toMutableList().also { it[i] = p.copy(where = Where.STAND, rot = p.type.flatRot) }
        var n = s.copy(pieces = pieces, selected = i, notice = Notice.NONE, restored = false)
        n = withMotion(n, i, listOf(fly(from, n.restPose(i), 250, 0.03f)), t)
        if (n.coach) n = flash(n.copy(coach = false), PlateFlash.TURN_HERE)
        if (n.welcome) { n = n.copy(welcome = false); onSettings(settings().copy(firstRunDone = true)) }
        set(startClock(n))
        log("SELECT piece $i ${p.type} -> stand S03")
        persist()
    }

    /** A pinch on a mouth pad (cx, cz): the selected block drops in that column (T3), or "pick a block first". */
    fun tapPad(cx: Int, cz: Int) {
        val s = st
        if (s.phase != Phase.PLAY) return
        val sel = s.selected
        if (sel == null) { set(s.copy(notice = Notice.PICK_FIRST)); log("PAD $cx,$cz without a block"); return }
        if (!inputOk()) { log("PAD ignored (busy)"); return }
        dropAt(sel, cx, cz, s.poseOf(sel, now()))
    }

    /** A wooden tab (T2): turns the block on the stand by 90°; taps during the 0.2 s turn add up (D45). */
    fun turn(tn: Turn) {
        val s = st
        if (s.phase != Phase.PLAY || s.confirm != null) return
        val i = s.selected ?: run { set(s.copy(notice = Notice.PICK_FIRST)); return }
        val t = now()
        if (s.motions[i]?.let { m -> !m.done(t) && m.segs.any { it.kind != Seg.Kind.TURN } } == true) { log("TURN ignored (flying)"); return }
        val p = s.pieces[i]
        val rot = Orient.turn(p.rot, tn)
        val from = s.poseOf(i, t)
        val pieces = s.pieces.toMutableList().also { it[i] = p.copy(rot = rot) }
        var n = s.copy(pieces = pieces, notice = Notice.NONE)
        n = withMotion(n, i, listOf(Seg(from, n.restPose(i), if (s.reduced) 120 else 200, Seg.Kind.TURN)), t)
        set(n)
        onSound(Sound.TURN, 1f)
        log("TURN $tn piece $i rot ${p.rot} -> $rot S04")
        persist()
    }

    private fun originOf(cells: List<Cell>) = Cell(cells.minOf { it.x }, cells.minOf { it.y }, cells.minOf { it.z })

    /** The drop rule from [from] (stand or the drag pose) at column (cx, cz): lands (S05 -> S07) or bounces back (S06). */
    private fun dropAt(i: Int, cx: Int, cz: Int, from: Pose) {
        var s = st
        val t = now()
        val p = s.pieces[i]
        val shape = Orient.cells(p.type, p.rot)
        val grid = s.grid()
        when (val r = grid.drop(shape, cx, cz)) {
            is DropResult.Lands -> {
                val o = originOf(r.cells)
                val above = Layout.aboveMouth(s.dims, p.type, p.rot, o.x, o.z)
                val pieces = s.pieces.toMutableList().also { it[i] = p.copy(where = Where.BOX, cells = r.cells) }
                var n = s.copy(pieces = pieces, selected = null, drag = null, moves = s.moves + 1, undo = (s.undo + UndoEntry.Drop(i, r.cells, p.rot)).takeLast(50), notice = Notice.NONE, restored = false)
                val land = n.restPose(i)
                val fallMs = if (s.reduced) 200L else 250L
                n = withMotion(n, i, listOf(fly(from, above, 300, Layout.ARC), Seg(above, land, fallMs, Seg.Kind.FALL, bounce = if (s.reduced) 0f else 0.003f, up = Layout.BOX.q.rotate(V3.UP))), t)
                n = startClock(n)
                gen++
                val holesBefore = grid.holes().size
                val g2 = n.grid()
                val newHoles = g2.holes().size > holesBefore
                if (newHoles) n = flash(n.copy(notice = Notice.HOLE), PlateFlash.HOLE)
                set(n)
                log("DROP piece $i ${p.type} at $cx,$cz -> ${r.cells} fall=${r.fall} holes=${g2.holes().size} S05")
                val g = gen
                scope.launch {
                    delay(300 + fallMs)
                    if (g != gen) return@launch
                    onSound(Sound.DROP, (1.06f - 0.05f * r.fall).coerceIn(0.9f, 1.1f))
                    if (newHoles) { delay(120); onSound(Sound.HOLE, 1f) }
                }
                if (g2.full) solveWhenLanded(300 + fallMs) else refreshSolver()
                persist()
            }
            DropResult.TooBig, is DropResult.SticksOut -> {
                val too = r == DropResult.TooBig
                val shapeSize = Orient.size(shape)
                val ox = (cx - anchorOf(shape).first).coerceIn(0, (s.dims.x - shapeSize.x).coerceAtLeast(0))
                val oz = (cz - anchorOf(shape).second).coerceIn(0, (s.dims.z - shapeSize.z).coerceAtLeast(0))
                val above = Layout.aboveMouth(s.dims, p.type, p.rot, ox, oz)
                var n = s.copy(drag = null, notice = if (too) Notice.TOO_BIG else Notice.STICKS_OUT)
                n = flash(n, if (too) PlateFlash.TOO_BIG else PlateFlash.STICKS_OUT)
                n = withMotion(n, i, listOf(fly(from, above, 300, Layout.ARC), Seg(above, above, if (s.reduced) 150 else 300, Seg.Kind.SHAKE), fly(above, n.restPose(i), 300, Layout.ARC)), t)
                set(n)
                log("REJECT piece $i at $cx,$cz ${if (too) "too big S06b" else "sticks out S06a"}")
                later(300) { onSound(Sound.REJECT, 1f) }
            }
        }
    }

    /** S17: lift a block out of the box onto the stand, selected, orientation kept; pinned blocks only shake. */
    private fun takeOut(i: Int) {
        val s = st
        val t = now()
        val grid = s.grid()
        val p = s.pieces[i]
        if (!grid.canTakeOut(i)) {
            set(flash(s.copy(notice = Notice.TAKE_BLOCKED, pinned = i to t), PlateFlash.TAKE_BLOCKED))
            onSound(Sound.REJECT, 0.95f)
            log("TAKE piece $i blocked S17")
            return
        }
        val from = s.poseOf(i, t)
        val o = originOf(p.cells)
        val above = Layout.aboveMouth(s.dims, p.type, p.rot, o.x, o.z)
        val pieces = s.pieces.toMutableList().also { it[i] = p.copy(where = Where.STAND, cells = emptyList()) }
        var n = s.copy(pieces = pieces, selected = i, moves = s.moves + 1, undo = (s.undo + UndoEntry.TakeOut(i, p.cells, p.rot)).takeLast(50), notice = Notice.TAKEN, restored = false)
        n = withMotion(n, i, listOf(fly(from, above, 250), fly(above, n.restPose(i), 300, Layout.ARC)), t)
        gen++
        set(startClock(n))
        onSound(Sound.TAKE, 1f)
        log("TAKE piece $i ${p.type} -> stand S17")
        refreshSolver()
        persist()
    }

    // ------------------------------------------------------------------ drag (T14, the main path)

    fun dragStart(i: Int): Boolean {
        val s = st
        if (i !in s.pieces.indices || !inputOk()) return false
        val p = s.pieces[i]
        if (p.where == Where.BOX) return false
        val t = now()
        var n = s
        if (p.where == Where.TRAY) {
            if (n.selected != null) n = deselect(n, t)
            val pieces = n.pieces.toMutableList().also { it[i] = p.copy(where = Where.STAND, rot = p.type.flatRot) }
            n = n.copy(pieces = pieces, selected = i)
            if (n.coach) n = n.copy(coach = false)
        }
        val base = s.poseOf(i, t)
        n = n.copy(drag = DragView(i, base, null, null, base), motions = n.motions - i, notice = Notice.NONE, restored = false)
        set(startClock(n))
        log("DRAG start piece $i ${p.type}")
        return true
    }

    /** [delta] = hand movement since the press (metres, volume frame). Column changes need 0.3 cell past the edge. */
    fun dragMove(delta: V3) {
        val s = st
        val d = s.drag ?: return
        val p = s.pieces[d.piece]
        val shape = Orient.cells(p.type, p.rot)
        val hand = Pose(d.base.p + delta, d.base.q)
        val (ax, az) = anchorOf(shape)
        val sh = Orient.shift(p.type, p.rot)
        val anchorWorld = hand.apply(V3((ax - sh.x) * Layout.U, 0f, (az - sh.z) * Layout.U).let { Q4.of(Orient.ROTS[p.rot]).conj().rotate(it) })
        val (fx, fz) = Layout.mouthCoords(s.dims, Layout.worldToBox(anchorWorld))
        val over = fx >= -1.5f && fx <= s.dims.x + 0.5f && fz >= -1.5f && fz <= s.dims.z + 0.5f
        var col: Pair<Int, Int>? = null
        if (over) {
            val prev = d.column
            val nx = if (prev == null || abs(fx - prev.first) > 0.8f) fx.roundToInt() else prev.first
            val nz = if (prev == null || abs(fz - prev.second) > 0.8f) fz.roundToInt() else prev.second
            col = nx.coerceIn(0, s.dims.x - 1) to nz.coerceIn(0, s.dims.z - 1)
        }
        val result = col?.let { s.grid().drop(shape, it.first, it.second) }
        val pose = when (result) {
            is DropResult.Lands -> originOf(result.cells).let { o -> Layout.aboveMouth(s.dims, p.type, p.rot, o.x, o.z) }
            is DropResult.SticksOut -> originOf(result.cells).let { o -> Layout.aboveMouth(s.dims, p.type, p.rot, o.x, o.z) }
            else -> hand
        }
        if (col != d.column) log("DRAG column $col ${result?.javaClass?.simpleName} f=(%.2f,%.2f)".format(fx, fz))
        set(s.copy(drag = d.copy(column = col, result = result, pose = pose)))
    }

    fun dragEnd() {
        val s = st
        val d = s.drag ?: return
        val col = d.column
        if (col != null) {
            log("DRAG end over ${col.first},${col.second}")
            set(s.copy(drag = null))
            dropAt(d.piece, col.first, col.second, d.pose)
        } else dragCancel()
    }

    fun dragCancel() {
        val s = st
        val d = s.drag ?: return
        val t = now()
        var n = s.copy(drag = null)
        n = withMotion(n, d.piece, listOf(fly(d.pose, n.restPose(d.piece), 300, Layout.ARC)), t)
        set(n)
        log("DRAG cancel piece ${d.piece} -> stand")
        persist()
    }

    // ------------------------------------------------------------------ panel buttons

    /** T6: one whole step back (a drop -> the block back to the tray, flat; a take-out -> the block back in place). */
    fun undo() {
        var s = st
        if (!s.canUndo || s.busy(now())) return
        val t = now()
        val e = s.undo.last()
        if (s.selected != null && s.selected != e.piece) s = deselect(s, t)
        val i = e.piece
        val p = s.pieces[i]
        val from = s.poseOf(i, t)
        var n: PlayState
        when (e) {
            is UndoEntry.Drop -> {
                val pieces = s.pieces.toMutableList().also { it[i] = p.copy(where = Where.TRAY, rot = p.type.flatRot, cells = emptyList()) }
                n = s.copy(pieces = pieces, selected = if (s.selected == i) null else s.selected)
                val o = originOf(e.cells)
                val above = Layout.aboveMouth(s.dims, p.type, e.rot, o.x, o.z)
                n = withMotion(n, i, listOf(fly(from, above, 220), fly(above, n.restPose(i), 300, 0.03f)), t)
                onSound(Sound.TAKE, 1f)
            }
            is UndoEntry.TakeOut -> {
                val pieces = s.pieces.toMutableList().also { it[i] = p.copy(where = Where.BOX, rot = e.rot, cells = e.cells) }
                n = s.copy(pieces = pieces, selected = if (s.selected == i) null else s.selected)
                val o = originOf(e.cells)
                val above = Layout.aboveMouth(s.dims, p.type, e.rot, o.x, o.z)
                n = withMotion(n, i, listOf(fly(from, above, 300, Layout.ARC), Seg(above, n.restPose(i), 250, Seg.Kind.FALL, bounce = if (s.reduced) 0f else 0.003f, up = Layout.BOX.q.rotate(V3.UP))), t)
                later(550) { onSound(Sound.DROP, 1f) }
            }
        }
        n = n.copy(undo = s.undo.dropLast(1), moves = (s.moves - 1).coerceAtLeast(0), notice = Notice.NONE, drag = null)
        gen++
        set(n)
        log("UNDO $e")
        refreshSolver()
        persist()
    }

    /** T7: the hint switch (a placement ghost + the block glows; "this won't fill" while it is on). */
    fun setHint(on: Boolean) {
        val s = st
        if (s.phase != Phase.PLAY) return
        set(s.copy(hintOn = on, usedHint = s.usedHint || on, stuck = if (on) s.stuck else false, notice = if (s.notice == Notice.STUCK && !on) Notice.NONE else s.notice))
        log("HINT ${if (on) "on" else "off"}")
        refreshSolver()
        persist()
    }

    /** Background solve for the hint ghost and the stuck check (design T9: <= 200 ms typical, 1 s cap). */
    private fun refreshSolver() {
        val s = st
        solverJob?.cancel()
        if (s.phase != Phase.PLAY || !s.hintOn) { if (s.hint != null || s.stuck) set(s.copy(hint = null, hintPiece = null, stuck = false)); return }
        val g = gen
        val mask = s.grid().mask()
        val left = s.pieces.filter { it.where != Where.BOX }.map { it.type }
        val dims = s.dims
        val t0 = System.nanoTime()
        solverJob = scope.launch {
            val r = withContext(engine) { runCatching { Solver.of(dims).next(mask, left, System.nanoTime() + 1_000_000_000L) } }
            if (g != gen) return@launch
            val cur = st
            val ms = (System.nanoTime() - t0) / 1_000_000
            val next = r.getOrNull()
            val timeout = r.exceptionOrNull() is Solver.Timeout
            val hp = next?.let { pl -> cur.selected?.takeIf { cur.pieces[it].type == pl.type } ?: cur.pieces.indices.firstOrNull { cur.pieces[it].where == Where.TRAY && cur.pieces[it].type == pl.type } }
            val stuck = next == null && !timeout && cur.holes().isEmpty()
            set(cur.copy(hint = next, hintPiece = hp, stuck = stuck, notice = if (stuck && cur.notice != Notice.HOLE) Notice.STUCK else if (!stuck && cur.notice == Notice.STUCK) Notice.NONE else cur.notice))
            log("SOLVER hint=${next?.type}@${next?.anchor} stuck=$stuck timeout=$timeout ${ms} ms")
        }
    }

    /** T8 / S09: the solver's next block flies to the stand, turns, and drops in (computed from the current box). */
    fun help() {
        var s = st
        if (s.phase != Phase.PLAY || s.confirm != null || s.busy(now()) || s.grid().full) return
        val g = gen
        set(s.copy(notice = Notice.THINKING))
        val mask = s.grid().mask()
        val left = s.pieces.filter { it.where != Where.BOX }.map { it.type }
        val dims = s.dims
        scope.launch {
            val r = withContext(engine) { runCatching { Solver.of(dims).next(mask, left, System.nanoTime() + 1_000_000_000L) } }
            if (g != gen) return@launch
            val pl = r.getOrNull()
            s = st
            if (pl == null) { set(s.copy(notice = Notice.HELP_NONE)); log("HELP none S09 -> S02"); return@launch }
            val t = now()
            val i = s.selected?.takeIf { s.pieces[it].type == pl.type }
                ?: s.pieces.indices.firstOrNull { s.pieces[it].where == Where.TRAY && s.pieces[it].type == pl.type }
                ?: s.pieces.indices.first { s.pieces[it].where != Where.BOX && s.pieces[it].type == pl.type }
            if (s.selected != null && s.selected != i) s = deselect(s, t)
            val p = s.pieces[i]
            val from = s.poseOf(i, t)
            val stand = Layout.onStand(p.type, pl.rot)
            val o = originOf(pl.cells)
            val above = Layout.aboveMouth(s.dims, p.type, pl.rot, o.x, o.z)
            // the drop rule must land it exactly there (solver placements are supported and clear above)
            val check = s.grid().drop(Orient.cells(p.type, pl.rot), pl.anchor.first, pl.anchor.second)
            if (check !is DropResult.Lands || check.cells.toSet() != pl.cells.toSet()) log("HELP mismatch $check vs ${pl.cells}")
            val pieces = s.pieces.toMutableList().also { it[i] = p.copy(where = Where.BOX, rot = pl.rot, cells = pl.cells) }
            var n = s.copy(pieces = pieces, selected = null, drag = null, moves = s.moves + 1, usedHelp = true,
                undo = (s.undo + UndoEntry.Drop(i, pl.cells, pl.rot)).takeLast(50), notice = Notice.HELP_HERE, restored = false)
            val land = n.restPose(i)
            n = withMotion(n, i, listOf(fly(from, Layout.onStand(p.type, p.rot), if (p.where == Where.STAND) 1 else 250, 0.03f),
                Seg(Layout.onStand(p.type, p.rot), stand, 200, Seg.Kind.TURN), Seg(stand, stand, 150, Seg.Kind.HOLD),
                fly(stand, above, 300, Layout.ARC), Seg(above, land, 250, Seg.Kind.FALL, bounce = if (s.reduced) 0f else 0.003f, up = Layout.BOX.q.rotate(V3.UP))), t)
            n = startClock(n)
            gen++
            set(n)
            val total = n.motions[i]!!.totalMs
            log("HELP piece $i ${p.type} -> ${pl.cells} S09")
            later(total) { onSound(Sound.DROP, 1f) }
            if (n.grid().full) solveWhenLanded(total) else refreshSolver()
            persist()
        }
    }

    fun requestRestart() {
        val s = st
        if (s.phase == Phase.LOADING) return
        if (!s.progress || s.phase == Phase.SOLVED) { restartNow(); return }
        set(s.copy(confirm = Confirm.Restart))
        log("CONFIRM restart S14")
    }

    fun chooseLevel(index: Int) {
        val s = st
        if (index !in Levels.ALL.indices) return
        if (index == s.levelIndex && s.phase == Phase.PLAY && !s.progress) return
        if (s.progress && s.phase == Phase.PLAY) { set(s.copy(confirm = Confirm.Switch(index))); log("CONFIRM switch -> ${Levels.ALL[index].id} S14"); return }
        load(index)
    }

    fun confirm() {
        when (val c = st.confirm) {
            Confirm.Restart -> restartNow()
            is Confirm.Switch -> load(c.level)
            null -> Unit
        }
    }

    fun cancelConfirm() { if (st.confirm != null) { set(st.copy(confirm = null)); log("CONFIRM cancelled") } }

    private fun restartNow() { val idx = st.levelIndex; load(idx); log("RESTART ${Levels.ALL[idx].id}") }

    /** T11: the next box (after S5 the first again). */
    fun next() { load((st.levelIndex + 1) % Levels.ALL.size) }

    /** T11: the same box again (the Soma count keeps adding up). */
    fun again() { load(st.levelIndex) }

    // ------------------------------------------------------------------ solved (S10)

    /**
     * M17 / QA K2: the box is full, but the last block is still flying / falling / bouncing for [ms]. The clock stops now
     * (the time is the time of the last move); S10 (lid, spin, result card) starts only after the drop animation has
     * played out. [悔一步] stays grey while the block is in the air (busy), so there is no undo in this window
     * (QA K7, deviation): the lid simply follows the landing. The guard below only covers a restart / box switch.
     */
    private fun solveWhenLanded(ms: Long) {
        val endedAt = now()
        val s = st
        set(s.copy(runningSince = null, elapsedMs = s.elapsed(endedAt)))
        log("FULL ${s.level.id}: S10 after the last block lands ($ms ms)")
        later(ms) {
            val n = st
            if (n.phase == Phase.PLAY && n.grid().full) solvedNow(n, now()) else log("FULL cancelled")
        }
    }

    private fun solvedNow(s0: PlayState, at: Long, restoring: Boolean = false) {
        val t = now()
        val s = s0.copy(runningSince = null, elapsedMs = s0.elapsed(t))
        val lv = s.level
        val counted = !s.usedHint && !s.usedHelp
        val recs = _records.value
        val old = recs.of(lv.id)
        val newBest = counted && (old.bestMs == null || s.elapsedMs < old.bestMs)
        var soma = recs.somaWays
        var somaNew = false
        if (lv.soma) {
            val g = s.grid()
            val key = Symmetry.canonical(lv.dims) { c -> s.pieces[g.at(c)].type }
            somaNew = key !in soma
            soma = soma + key
        }
        val rec = old.copy(
            done = true,
            bestMs = if (counted) listOfNotNull(old.bestMs, s.elapsedMs).minOrNull() else old.bestMs,
            bestMoves = if (counted) listOfNotNull(old.bestMoves, s.moves).minOrNull() else old.bestMoves,
        )
        val nr = recs.copy(boxes = recs.boxes + (lv.id to rec), somaWays = soma)
        if (!restoring) { _records.value = nr; runCatching { recordStore.save(nr) } }
        val result = Result(s.elapsedMs, s.moves, counted, newBest && !restoring, rec.bestMs, if (lv.soma) soma.size else null, somaNew && !restoring)
        set(s.copy(phase = Phase.SOLVED, solvedAt = if (restoring) t - 10_000 else at, result = result, hint = null, hintPiece = null, stuck = false, notice = Notice.NONE, selected = null, drag = null))
        log("SOLVED S10 ${lv.id} ${s.elapsedMs} ms ${s.moves} moves counted=$counted newBest=$newBest soma=${result.somaFound}")
        if (!restoring) {
            val lid = (at - t).coerceAtLeast(0) + 500
            later(lid) { onSound(Sound.LID, 1f) }
            later(lid + 350) { onSound(Sound.SOLVED, 1f) }
        }
        persist()
    }

    // ------------------------------------------------------------------ lifecycle

    fun onBackground() {
        foreground = false
        val s = st
        if (s.runningSince != null) set(s.copy(elapsedMs = s.elapsed(now()), runningSince = null))
        if (s.drag != null) dragCancel()
        persist()
        log("BACKGROUND saved")
    }

    fun onForeground() {
        foreground = true
        val s = st
        if (s.phase == Phase.PLAY && s.runningSince == null && s.progress) set(s.copy(runningSince = now()))
    }

    fun setReduced(on: Boolean) { set(st.copy(reduced = on)) }

    fun clearRecords() {
        val r = Records()
        _records.value = r
        runCatching { recordStore.save(r) }
        log("RECORDS cleared")
    }

    // ------------------------------------------------------------------ debug (same calls as the user's input)

    /** QA hold: motions stop [ms] after their start (null = release). */
    fun freeze(ms: Long?) { set(st.copy(freezeMs = ms)) }

    /** Debug drag to column (cx, cz) through dragStart / dragMove / dragEnd (T14). */
    fun debugDrag(i: Int, cx: Int, cz: Int, release: Boolean = true) {
        if (!dragStart(i)) { log("DEBUG drag refused"); return }
        val s = st
        val d = s.drag ?: return
        val p = s.pieces[i]
        val shape = Orient.cells(p.type, p.rot)
        val (ax, az) = anchorOf(shape)
        val sh = Orient.shift(p.type, p.rot)
        val anchorOff = Q4.of(Orient.ROTS[p.rot]).conj().rotate(V3((ax - sh.x) * Layout.U, 0f, (az - sh.z) * Layout.U))
        val startAnchor = d.base.apply(anchorOff)
        val target = Layout.boxToWorld(Layout.mouthLocal(s.dims, cx, cz) + V3(0f, Layout.HOVER, 0f))
        val delta = target - startAnchor
        scope.launch {
            for (k in 1..6) { delay(60); dragMove(delta * (k / 6f)) }
            if (release) { delay(250); dragEnd() }
        }
    }

    fun autoSolve(stepMs: Long) {
        autoJob?.cancel()
        autoJob = scope.launch {
            while (st.phase == Phase.PLAY) {
                while (st.busy(now()) || st.notice == Notice.THINKING) delay(50)
                if (st.phase != Phase.PLAY || st.grid().full) break
                val before = st.placed
                help()
                delay(stepMs)
                while (st.busy(now()) || st.notice == Notice.THINKING) delay(50)
                if (st.placed == before) break
            }
        }
    }

    fun stopAuto() { autoJob?.cancel() }

    /** Debug: put the design's known solution's first [n] steps into the box (QA setups), then S02. */
    fun debugPlaceSolution(n: Int) {
        val s = st
        val lv = s.level
        val pieces = lv.pieces.map { PieceState(it, Where.TRAY, it.flatRot) }.toMutableList()
        val used = HashSet<Int>()
        lv.solution.take(n).forEach { step ->
            val i = pieces.indices.first { it !in used && pieces[it].type == step.piece }
            used += i
            pieces[i] = PieceState(step.piece, Where.BOX, Orient.rotFor(step.piece, step.cells)!!, step.cells)
        }
        gen++
        set(s.copy(pieces = pieces, selected = null, moves = n, undo = emptyList(), motions = emptyMap(), drag = null, phase = Phase.PLAY, notice = Notice.NONE, confirm = null, welcome = false, coach = false))
        refreshSolver()
        persist()
    }

    /** QA hold of S10: the box full, [ms] after the last block landed (lid 0..500, lift 600..900, turn 900..2100). */
    fun debugHoldSolved(ms: Long) {
        val s = st
        if (s.phase != Phase.SOLVED) return
        set(s.copy(motions = emptyMap(), solvedAt = now() - ms, freezeMs = ms))
    }

    /** Debug: block [i] straight to the stand in orientation [rot] (QA "selected L" shots). */
    fun debugSelect(i: Int, rot: Int? = null) {
        select(i)
        rot?.let { r -> val s = st; set(s.copy(pieces = s.pieces.toMutableList().also { it[i] = it[i].copy(rot = r) }, motions = s.motions - i)) }
    }

    fun dump(): String {
        val s = st
        val g = s.grid()
        return "level=${s.level.id} phase=${s.phase} placed=${s.placed}/${s.pieces.size} sel=${s.selected} moves=${s.moves} t=${s.elapsed(now())} " +
            "hint=${s.hintOn}:${s.hint?.type}@${s.hint?.anchor} stuck=${s.stuck} holes=${g.holes()} notice=${s.notice} confirm=${s.confirm} " +
            "pieces=${s.pieces.mapIndexed { i, p -> "$i:${p.type}/${p.where}/r${p.rot}${if (p.where == Where.BOX) p.cells.toString() else ""}" }} " +
            "depth=${(0 until s.dims.z).joinToString("|") { z -> (0 until s.dims.x).joinToString("") { x -> g.depth(x, z).toString() } }}"
    }
}
