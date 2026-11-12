package com.kejing.puzzlebox.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.kejing.puzzlebox.domain.Cell
import com.kejing.puzzlebox.domain.Dims
import com.kejing.puzzlebox.domain.DropResult
import com.kejing.puzzlebox.domain.GameFlow
import com.kejing.puzzlebox.domain.Layout
import com.kejing.puzzlebox.domain.Orient
import com.kejing.puzzlebox.domain.Phase
import com.kejing.puzzlebox.domain.PieceType
import com.kejing.puzzlebox.domain.PlayState
import com.kejing.puzzlebox.domain.Pose
import com.kejing.puzzlebox.domain.Q4
import com.kejing.puzzlebox.domain.Turn
import com.kejing.puzzlebox.domain.V3
import com.kejing.puzzlebox.domain.Where
import com.kejing.puzzlebox.domain.anchorOf
import com.kejing.puzzlebox.platform.TAG
import com.kejing.puzzlebox.ui.board.input.GazeTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Loads the GLB templates once (asset://models/<name>.glb) and hands out clones. */
internal class Models(private val load: suspend (String) -> Entity) {
    private val cache = HashMap<String, Entity>()
    suspend fun get(name: String): Entity = cache[name] ?: load(name).also { cache[name] = it }
    fun has(name: String) = name in cache
}

/** The words on the three tabs and the plate lines, already in the app language (resolved by BoardScreen). */
internal data class SceneTexts(val tabs: Map<Turn, String>, val plate1: String, val plate2: String, val plate2Accent: Boolean = false, val testF: Boolean = false)

/**
 * Every entity of the volume. Compose hands over [PlayState] through [apply]; everything that moves (blocks flying,
 * turning, falling; the lid; the box lifting and spinning) is placed in [onFrame], driven by BoardFrameSystem (ECS,
 * never a Compose-driven per-frame loop).
 *
 * ```
 * root (volume centre)
 * ├ wedge, tray, stand (+ tab bracket), sign stand + name plate "plate" left of the box (q1-1; collider only in S15), key light   (static, volume coords)
 * ├ tabs "tab:yaw|pitch|roll" (GLB holder: collider + Interactable + HoverEffect; face plane child), only while selected
 * ├ blocks "pc:<i>" (GLB clone root = holder: one cube collider per cell + Interactable + HoverEffect)
 * ├ anchor dot (on the stand block's anchor top face), tray outline (where the lifted block sat)
 * └ boxRoot (Layout.BOX x rise / lift / spin)
 *   ├ box_<X>_<Y>_<Z>.glb (no hits), four acrylic planes, mouth overlay (grid + ticks)
 *   ├ pads "pad:<x>:<z>" (whole-cell collider + <= 5 mm marker child + HoverEffect), enabled while a block is selected
 *   ├ hole cubes (dark red), ghost cubes (drop preview white / red, hint in the block colour)
 *   └ lid (only when full)
 * ```
 */
internal class BoardScene(private val scope: CoroutineScope) {
    val root: Entity = Entity().apply { setName("root") }
    private val boxRoot = Entity().apply { setName("boxRoot") }

    private var res: SceneResources? = null
    private var models: Models? = null
    private var fallback: Entity? = null
    private var built = false

    private var state = PlayState()
    private var texts = SceneTexts(emptyMap(), "", "")
    private var panelOpen = true
    private var regionHalfY = -1f

    private val pieces = ArrayList<Entity>()
    private val pieceInBox = ArrayList<Boolean?>()
    private var levelBuilt = -1
    private var levelLoading = -1
    private var boxModel: Entity? = null
    private var lid: Entity? = null
    private val acrylic = ArrayList<Entity>()
    private val acrylicMeshes = HashMap<String, MeshResource>()
    private var mouth: ModelEntity? = null
    private var mouthMat: UnlitMaterial? = null
    private var mouthTex: TextureResource? = null
    private var mouthKey: String? = null
    private var mouthBusy = false
    private val pads = HashMap<Pair<Int, Int>, Entity>()
    private var padsOn = false
    private val holeCubes = ArrayList<ModelEntity>()
    private val ghostCubes = ArrayList<ModelEntity>()
    private val tabs = HashMap<Turn, Entity>()
    private val tabMats = HashMap<Turn, UnlitMaterial>()
    private val tabTex = HashMap<Turn, TextureResource>()
    private var tabKey: String? = null
    private var tabBusy = false
    private var tabsOn: Boolean? = null
    private var plate: ModelEntity? = null
    private var plateMat: UnlitMaterial? = null
    private var plateTex: TextureResource? = null
    private var plateShown: String? = null
    private var plateBusy = false
    private var plateCollider = false
    private var plateLine2Logged: String? = null
    private var dot: ModelEntity? = null
    private val outlines = HashMap<PieceType, ModelEntity>()
    private val outlineMats = HashMap<PieceType, UnlitMaterial>()
    private val outlineMeshes = HashMap<PieceType, MeshResource>()

    @Volatile private var hover: String? = null

    /** D50 hover: the holder under a resting pointer (system HoverEffect draws it; this only logs). */
    fun setHover(name: String?) {
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    init { root.addChild(boxRoot) }

    val ready: Boolean get() = built && levelBuilt == state.levelIndex

    /** The SpatialView's half height (the volume is 0.40 m high: 0.20); the design coordinates are volume-centre based. */
    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        Log.i(TAG, "SCENE region halfY=$halfY (design 0.20)")
    }

    private fun Entity.at(p: V3, q: Q4? = null) {
        transform().setPosition(Vector3(p.x, p.y, p.z))
        q?.let { transform().setQuaternion(Quat(it.x, it.y, it.z, it.w)) }
    }

    private fun Entity.pose(p: Pose) = at(p.p, p.q)

    private val flatQ = Q4.axis(V3(1f, 0f, 0f), (-PI / 2).toFloat())

    /** S00 stand-in: the tray and an empty box outline as plain slabs until the models are in (design lifecycle "首帧兜底"). */
    fun showFallback(slab: MeshResource, tray: MeshResource, material: com.pico.spatial.core.ecs.resource.Material) {
        if (built || fallback != null) return
        val f = Entity().apply { setName("fallback") }
        f.addChild(ModelEntity(slab, material).apply { at(Layout.BOX.apply(V3(0f, 0.005f, -0.08f)), Layout.BOX.q) })
        f.addChild(ModelEntity(tray, material).apply { at(V3(0f, -0.1925f, 0.15f)) })
        root.addChild(f)
        fallback = f
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    /** Static furniture + tabs + plate + pads; the level's box and blocks come in [buildLevel]. */
    suspend fun install(r: SceneResources, m: Models) {
        if (built) { r.close(); return }
        res = r; models = m
        val t0 = SystemClock.elapsedRealtime()
        for (n in listOf("wedge", "tray", "stand", "signstand")) root.addChild(clone(m.get(n)).apply { setName(n) })
        // three tabs on the stand's front edge, facing the user (D10: only turned about Y; they face +Z already)
        val tabTpl = m.get("tab")
        for (t in Turn.entries) {
            val e = clone(tabTpl).apply { setName(BoardNames.tab(t)) }
            e.at(Layout.tabCentre(t))
            GazeTargets.install(e, r.tabShape, r.physics)
            val mat = SceneResources.unlit(androidx.compose.ui.graphics.Color.White, tex = null).also { r.keep(it) }
            tabMats[t] = mat
            e.addChild(ModelEntity(r.tabPlane, mat).apply { setName("tabFace:${t.name}"); at(V3(0f, 0f, Layout.TAB_T / 2 + 0.0006f)) })
            e.enabled = false
            root.addChild(e); tabs[t] = e
        }
        plateMat = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setCullingMode(MaterialCullingMode.BACK); setApplyToneMapping(false) }.also { r.keep(it) }
        plate = ModelEntity(r.platePlane, plateMat!!).apply { setName(BoardNames.PLATE); at(Layout.PLATE_C, Layout.PLATE_Q) }
        root.addChild(plate!!)
        mouthMat = SceneResources.unlit(androidx.compose.ui.graphics.Color.White, opacity = 0.999f).also { r.keep(it) }
        // pads: 4 x 4 holders (the largest mouth), repositioned per level
        for (x in 0 until 4) for (z in 0 until 4) {
            val name = BoardNames.pad(x, z)
            val h = GazeTargets.cells(boxRoot, mapOf(name to Vector3(0f, 0f, 0f)), r.padShape, r.physics, r.markerParts, r.marker)[name]!!
            h.enabled = false
            pads[x to z] = h
        }
        repeat(27) { holeCubes += ModelEntity(r.cellCube, r.holeRed).apply { setName("hole:$it"); enabled = false; boxRoot.addChild(this) } }
        repeat(6) { ghostCubes += ModelEntity(r.cellCube, r.ghostWhite).apply { setName("ghost:$it"); enabled = false; boxRoot.addChild(this) } }
        dot = ModelEntity(r.dotPlane, r.dotMat).apply { setName("anchorDot"); enabled = false; root.addChild(this) }
        buildLight()
        fallback?.let { root.removeChild(it) }; fallback = null
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms")
        buildLevel()
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: one moderate warm key light from the front right, above (no dynamic point lights, design budget.lighting)
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.8f, true))
            val q = Q4.axis(V3(0f, 1f, 0f), (25 * PI / 180).toFloat()) * Q4.axis(V3(1f, 0f, 0f), (-55 * PI / 180).toFloat())
            light.at(V3.ZERO, q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    /** (Re)builds the box model, acrylic walls, lid and the blocks for the current level. */
    private fun buildLevel() {
        val st = state
        val idx = st.levelIndex
        if (!built || levelLoading == idx || st.pieces.isEmpty()) return
        levelLoading = idx
        val r = res ?: return
        val m = models ?: return
        scope.launch {
            val lv = st.level
            val d = lv.dims
            val t0 = SystemClock.elapsedRealtime()
            val boxTpl = m.get("box_${d.x}_${d.y}_${d.z}")
            val lidTpl = m.get("lid_${d.x}_${d.z}")
            val pieceTpls = lv.pieces.distinct().associateWith { m.get("piece_${it.name}") }
            val wallMeshes = withContext(Dispatchers.IO) { acrylicMeshesFor(r, d) }
            val walls = acrylicFor(r, d, wallMeshes)
            val mouthMesh = withContext(Dispatchers.IO) { MeshResource.createPlane(d.x * Layout.U, d.z * Layout.U, 0f).also { r.keep(it) } }
            withContext(Dispatchers.IO) { lv.pieces.distinct().forEach { outlineFor(r, it) } }
            if (state.levelIndex != idx) { levelLoading = -1; buildLevel(); return@launch }
            // clear the old box
            boxModel?.let { boxRoot.removeChild(it) }
            lid?.let { boxRoot.removeChild(it) }
            acrylic.forEach { boxRoot.removeChild(it) }; acrylic.clear()
            mouth?.let { boxRoot.removeChild(it) }; mouth = null; mouthKey = null
            pieces.forEach { root.removeChild(it) }; pieces.clear(); pieceInBox.clear()
            outlines.values.forEach { root.removeChild(it) }; outlines.clear()
            // box + walls + lid
            boxModel = clone(boxTpl).apply { setName("boxModel") }.also { boxRoot.addChild(it) }
            walls.forEach { boxRoot.addChild(it); acrylic += it }
            lid = clone(lidTpl).apply { setName("lid"); enabled = false }.also { boxRoot.addChild(it) }
            mouth = ModelEntity(mouthMesh, mouthMat!!).apply {
                setName("mouthOverlay"); at(V3(0f, Layout.mouthY(d) + 0.0012f, -Layout.MARGIN - Layout.innerD(d) / 2), flatQ)
            }.also { boxRoot.addChild(it) }
            for ((xz, h) in pads) {
                val (x, z) = xz
                val inside = x < d.x && z < d.z
                if (inside) h.at(Layout.mouthLocal(d, x, z) + V3(0f, 0.002f, 0f))
                h.enabled = false
            }
            padsOn = false
            // blocks
            lv.pieces.forEachIndexed { i, t ->
                val e = clone(pieceTpls.getValue(t)).apply { setName(BoardNames.piece(i)) }
                e.components.set(InteractableComponent())
                e.components.set(HoverEffectComponent())
                root.addChild(e)
                pieces += e; pieceInBox += null
            }
            for (t in lv.pieces.distinct()) outlines[t] = ModelEntity(outlineMeshes.getValue(t), outlineMats.getValue(t)).apply { setName("trayOutline:${t.name}"); enabled = false }.also { root.addChild(it) }
            levelBuilt = idx
            levelLoading = -1
            Log.i(TAG, "SCENE level ${lv.id} ${d} built in ${SystemClock.elapsedRealtime() - t0} ms")
            onFrame(0f)
        }
    }

    private fun acrylicMeshesFor(r: SceneResources, d: Dims): Pair<MeshResource, MeshResource> {
        val h = d.y * Layout.U
        fun mesh(a: Float, b: Float) = synchronized(acrylicMeshes) { acrylicMeshes.getOrPut("%.3fx%.3f".format(a, b)) { MeshResource.createPlane(a, b, 0f).also { r.keep(it) } } }
        return mesh(Layout.innerW(d) + 0.008f, h) to mesh(Layout.innerD(d) + 0.008f, h)
    }

    /** Four 4 mm acrylic walls (one translucent plane each, both faces drawn), inside the corner posts. */
    private fun acrylicFor(r: SceneResources, d: Dims, m: Pair<MeshResource, MeshResource>): List<Entity> {
        val h = d.y * Layout.U
        val yc = Layout.FLOOR_T + h / 2
        val zc = -Layout.MARGIN - Layout.innerD(d) / 2
        val front = ModelEntity(m.first, r.acrylic).apply { setName("acrylic:front"); at(V3(0f, yc, -Layout.MARGIN + 0.004f)) }
        val back = ModelEntity(m.first, r.acrylic).apply { setName("acrylic:back"); at(V3(0f, yc, -Layout.MARGIN - Layout.innerD(d) - 0.004f)) }
        val side = Q4.axis(V3(0f, 1f, 0f), (PI / 2).toFloat())
        val left = ModelEntity(m.second, r.acrylic).apply { setName("acrylic:left"); at(V3(-Layout.innerW(d) / 2 - 0.004f, yc, zc), side) }
        val right = ModelEntity(m.second, r.acrylic).apply { setName("acrylic:right"); at(V3(Layout.innerW(d) / 2 + 0.004f, yc, zc), side) }
        return listOf(back, left, right, front)
    }

    private fun outlineFor(r: SceneResources, t: PieceType) {
        if (t in outlineMats) return
        val s = Orient.size(Orient.cells(t, t.flatRot))
        outlineMeshes[t] = MeshResource.createPlane(s.x * Layout.U, s.z * Layout.U, 0f).also { r.keep(it) }
        outlineMats[t] = SceneResources.unlit(androidx.compose.ui.graphics.Color.White, tex = TextureResource.create(BoardTextures.outline(t)).also { r.keep(it) }).also { r.keep(it) }
    }

    fun apply(next: PlayState, panelOpen: Boolean, texts: SceneTexts) {
        state = next
        this.panelOpen = panelOpen
        this.texts = texts
        if (!built) return
        if (levelBuilt != next.levelIndex) buildLevel()
        onFrame(0f)
    }

    // ---------------------------------------------------------------- textures (async, keyed)

    private fun refreshPlate() {
        val key = "${texts.plate1}\n${texts.plate2}\n${texts.plate2Accent}"
        if (key == plateShown || plateBusy || texts.plate1.isEmpty()) return
        plateBusy = true
        val (l1, l2) = texts.plate1 to texts.plate2
        val accent = texts.plate2Accent
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(BoardTextures.plate(l1, l2, accent)) }.getOrNull() }
            if (tex != null) {
                plateMat?.setBaseColorTexture(tex)
                plateTex?.let { old -> runCatching { old.close() } }
                plateTex = tex
                plateShown = key
                // the clock ticks every second: log only when the second line is a reason / the S15 line, or it changes kind
                val logKey = l1 + "|" + (if (accent) l2 else "clock")
                if (logKey != plateLine2Logged) { plateLine2Logged = logKey; Log.i(TAG, "PLATE \"$l1 | $l2\" accent=$accent") }
            }
            plateBusy = false
        }
    }

    private fun refreshTabs() {
        val key = Turn.entries.joinToString("|") { texts.tabs[it].orEmpty() } + texts.testF
        if (key == tabKey || tabBusy || texts.tabs.isEmpty()) return
        tabBusy = true
        val words = texts.tabs
        val f = texts.testF
        scope.launch {
            val made = withContext(Dispatchers.IO) { Turn.entries.associateWith { t -> runCatching { TextureResource.create(BoardTextures.tab(t, words[t].orEmpty(), f)) }.getOrNull() } }
            for ((t, tex) in made) {
                if (tex == null) continue
                tabMats[t]?.setBaseColorTexture(tex)
                tabTex[t]?.let { runCatching { it.close() } }
                tabTex[t] = tex
            }
            tabKey = key
            tabBusy = false
            Log.i(TAG, "TABS \"$key\"")
        }
    }

    private fun refreshMouth(st: PlayState, lit: Boolean) {
        val d = st.dims
        val g = st.grid()
        val depths = (0 until d.z).joinToString("|") { z -> (0 until d.x).joinToString("") { x -> g.depth(x, z).toString() } }
        val key = "${st.levelIndex}:$depths:$lit"
        if (key == mouthKey || mouthBusy) return
        mouthBusy = true
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(BoardTextures.mouth(d, { x, z -> g.depth(x, z) }, lit)) }.getOrNull() }
            if (tex != null) {
                mouthMat?.setBaseColorTexture(tex)
                mouthTex?.let { runCatching { it.close() } }
                mouthTex = tex
                mouthKey = key
                Log.i(TAG, "MOUTH ticks $depths lit=$lit")
            }
            mouthBusy = false
        }
    }

    // ---------------------------------------------------------------- per frame

    /** The box pose now: rise on a new box, lift + one turn after it is full (design solvedSpin). */
    fun boxPose(st: PlayState, now: Long): Pose {
        val d = st.dims
        val up = Layout.BOX.q.rotate(V3.UP)
        var pose = Layout.BOX
        val rise = (now - st.levelAt).coerceAtLeast(0)
        if (rise < 400 && st.phase != Phase.SOLVED && !st.reduced) {
            val k = 1f - rise / 400f
            pose = Pose(pose.p - up * (0.03f * k * k), pose.q)
        }
        if (st.phase == Phase.SOLVED && !st.reduced) {
            val e = clampFreeze(st, now - st.solvedAt)
            // lid 0..500, lift 600..900, turn 900..2100, settle 2100..2400
            val lift = when {
                e < 600 -> 0f
                e < 900 -> (e - 600) / 300f
                e < 2100 -> 1f
                e < 2400 -> 1f - (e - 2100) / 300f
                else -> 0f
            } * 0.05f
            val turn = if (e in 900..2100) ((e - 900) / 1200f) else 0f
            val c = Layout.boxCentreLocal(d)
            val spin = Q4.axis(V3.UP, (2 * PI * smooth(turn)).toFloat())
            // box frame: translate to centre, spin about the box's own up, back, then lift along up
            val local = Pose(c + V3(0f, lift, 0f), spin) * Pose(V3.ZERO - c)
            pose = pose * local
        }
        return pose
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun clampFreeze(st: PlayState, e: Long) = st.freezeMs?.let { min(e, it) } ?: e

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        if (!built) return
        val r = res ?: return
        refreshPlate()
        refreshTabs()
        val st = state
        val now = SystemClock.uptimeMillis()
        // name plate: a collider only while the panel is closed (S15, design input.rule)
        if (plateCollider != !panelOpen) {
            plateCollider = !panelOpen
            plate?.let { p ->
                if (plateCollider) GazeTargets.install(p, r.plateShape, r.physics)
                else { p.components.remove(CollisionComponent::class.java); p.components.remove(InteractableComponent::class.java); p.components.remove(HoverEffectComponent::class.java) }
            }
        }
        if (levelBuilt != st.levelIndex || pieces.size != st.pieces.size) return
        val d = st.dims
        val bp = boxPose(st, now)
        boxRoot.pose(bp)
        val boxInv = Pose(V3.ZERO - Layout.BOX.q.conj().rotate(Layout.BOX.p), Layout.BOX.q.conj())
        val selected = st.selected?.takeIf { st.pieces[it].where == Where.STAND }
        refreshMouth(st, selected != null)

        // ---------------- blocks
        val coach = if (st.coach && st.phase == Phase.PLAY) coachPiece(st) else null
        val bobOn = coach ?: st.hintPiece?.takeIf { st.hintOn && st.pieces[it].where == Where.TRAY && st.drag == null }
        for (i in pieces.indices) {
            val e = pieces[i]
            val p = st.pieces[i]
            var pose = st.poseOf(i, now)
            val moving = st.drag?.piece == i || st.motions[i]?.let { st.moving(it, now) } == true
            if (p.where == Where.BOX && !moving && st.phase == Phase.SOLVED) pose = bp * (boxInv * pose)
            if (p.where == Where.BOX && !moving && st.phase != Phase.SOLVED && now - st.levelAt < 400) pose = bp * (boxInv * pose)
            if (i == bobOn && !moving) {
                val ph = ((now - st.levelAt) % 1500) / 1500f
                val k = if (ph < 0.3f) sin(PI.toFloat() * ph / 0.3f) else 0f
                pose = Pose(pose.p + V3(0f, 0.008f * k, 0f), pose.q)
            }
            // blocked take-out (S17): a short shake in place
            st.pinned?.takeIf { it.first == i && now - it.second < 300 }?.let { (_, at) ->
                pose = Pose(pose.p + V3(0.003f * sin((now - at) / 300f * 6 * PI.toFloat()), 0f, 0f), pose.q)
            }
            e.pose(pose)
            // colliders: grown 1 cm in the tray / on the stand, exact in the box (design hitTargets)
            val inBox = p.where == Where.BOX
            if (pieceInBox[i] != inBox) {
                pieceInBox[i] = inBox
                val shapes: List<ShapeResource> = if (inBox) r.exactShapes.getValue(p.type) else r.bigShapes.getValue(p.type)
                e.components.set(CollisionComponent(collisionShape = shapes, physicsMaterial = r.physics))
            }
        }

        // ---------------- tabs (only with a block on the stand), anchor dot, tray outline
        val tabsWanted = selected != null && st.phase == Phase.PLAY && st.drag == null
        if (tabsOn != tabsWanted) { tabsOn = tabsWanted; tabs.values.forEach { it.enabled = tabsWanted } }
        dot?.let { dt ->
            val i = selected
            if (i == null || st.drag != null || st.motions[i]?.let { m -> st.moving(m, now) && m.segs.any { it.kind != com.kejing.puzzlebox.domain.Seg.Kind.TURN } } == true) { dt.enabled = false } else {
                val p = st.pieces[i]
                val cells = Orient.cells(p.type, p.rot)
                val (ax, az) = anchorOf(cells)
                val top = cells.filter { it.x == ax && it.z == az }.maxOf { it.y }
                val pose = st.poseOf(i, now)
                val s = Orient.shift(p.type, p.rot)
                val off = V3((ax - s.x) * Layout.U, (top - s.y) * Layout.U + Layout.CUBE / 2 + 0.0008f, (az - s.z) * Layout.U)
                dt.at(pose.p + off, flatQ)
                dt.enabled = true
            }
        }
        val outlineType = st.selected?.takeIf { st.pieces[it].where != Where.BOX }?.let { st.pieces[it].type }
        for ((t, o) in outlines) {
            if (t != outlineType) { o.enabled = false; continue }
            val slot = st.tray.slots[st.selected!!]
            val zc = if (slot.row == 0) Layout.ROW_BACK_Z else Layout.ROW_FRONT_Z
            o.at(V3(slot.x0 + slot.w / 2, Layout.TRAY_TOP + 0.0008f, zc), flatQ)
            o.enabled = true
        }

        // ---------------- pads (only while a block is selected and nothing flies)
        val wantPads = selected != null && st.phase == Phase.PLAY && !st.busy(now)
        if (wantPads != padsOn) {
            padsOn = wantPads
            for ((xz, h) in pads) h.enabled = wantPads && xz.first < d.x && xz.second < d.z
        }

        // ---------------- holes (always), ghost (drag preview, else the hint)
        val holes = st.holes()
        for (k in holeCubes.indices) {
            val c = holeCubes[k]
            val cell = holes.getOrNull(k)
            if (cell == null) { c.enabled = false; continue }
            c.at(Layout.cellLocal(d, cell)); c.enabled = true
        }
        var ghostCells: List<Cell> = emptyList()
        var ghostMat = r.ghostWhite
        val drag = st.drag
        if (drag != null) {
            when (val res2 = drag.result) {
                is DropResult.Lands -> { ghostCells = res2.cells; ghostMat = r.ghostWhite }
                is DropResult.SticksOut -> { ghostCells = res2.cells; ghostMat = r.ghostRed }
                else -> Unit
            }
        } else if (st.hintOn && st.phase == Phase.PLAY && !st.busy(now)) {
            st.hint?.let { h -> ghostCells = h.cells; ghostMat = r.hintMat.getValue(h.type) }
        }
        if (ghostCells.isNotEmpty() && drag == null) (ghostMat as? UnlitMaterial)?.setOpacity(SceneResources.hintOpacity(now, st.reduced))
        for (k in ghostCubes.indices) {
            val c = ghostCubes[k]
            val cell = ghostCells.getOrNull(k)
            if (cell == null) { c.enabled = false; continue }
            c.components[ModelComponent::class.java]?.materials?.set(0, ghostMat)
            c.at(Layout.cellLocal(d, cell)); c.enabled = true
        }

        // ---------------- lid (design spatialLayout.lid: falls 0.15 m along the box's down in 0.5 s, then stays)
        lid?.let { l ->
            if (st.phase != Phase.SOLVED) { l.enabled = false } else {
                val e = clampFreeze(st, now - st.solvedAt)
                val k = if (st.reduced) 1f else (e / 500f).coerceIn(0f, 1f)
                val y = Layout.mouthY(d) + 0.002f + Layout.LID_DROP * (1f - k * k)
                l.at(V3(0f, y, -Layout.MARGIN - Layout.innerD(d) / 2))
                l.enabled = true
            }
        }
    }

    /** The block the coach points at (S01): the leftmost tray block. */
    private fun coachPiece(st: PlayState): Int? = st.pieces.indices.filter { st.pieces[it].where == Where.TRAY }.minByOrNull { i -> st.tray.slots[i].let { it.x0 * 10 + it.row } }

    /** Debug hit replay (template README 必带单测 4): the holder and its deepest child (marker / GLB mesh node). */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    fun evidence(): String {
        val st = state
        return "level=${st.level.id} built=$levelBuilt halfY=$regionHalfY pads=$padsOn tabs=$tabsOn plateCollider=$plateCollider plate=\"${plateShown?.replace("\n", " | ")}\" plateAt=${Layout.PLATE_C} " +
            "box=${boxPose(st, SystemClock.uptimeMillis()).p} pieces=${pieces.mapIndexed { i, e -> "$i@${e.transform().position}" }}"
    }

    fun dispose() {
        plateTex?.let { runCatching { it.close() } }
        mouthTex?.let { runCatching { it.close() } }
        tabTex.values.forEach { runCatching { it.close() } }
        res?.close()
        res = null
    }
}

/** Every holder name (one place; never built inside Entity().apply {} where `id` means the entity's id, build.md). */
object BoardNames {
    const val PLATE = "plate"
    fun piece(i: Int) = "pc:$i"
    fun pad(x: Int, z: Int) = "pad:$x:$z"
    fun tab(t: Turn) = "tab:${t.name.lowercase()}"

    sealed interface Hit
    data class PieceHit(val index: Int) : Hit
    data class PadHit(val x: Int, val z: Int) : Hit
    data class TabHit(val turn: Turn) : Hit
    data object PlateHit : Hit
    data object NoHit : Hit

    /** A holder name -> what was hit; out-of-range names resolve to nothing (a naming bug shows in the log). */
    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        val f = name.split(":")
        return when (f[0]) {
            "pc" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { f.size == 2 && it in 0..6 }?.let { PieceHit(it) } ?: NoHit
            "pad" -> {
                val x = f.getOrNull(1)?.toIntOrNull(); val z = f.getOrNull(2)?.toIntOrNull()
                if (f.size == 3 && x in 0..3 && z in 0..3) PadHit(x!!, z!!) else NoHit
            }
            "tab" -> if (f.size == 2) Turn.entries.firstOrNull { it.name.lowercase() == f[1] }?.let { TabHit(it) } ?: NoHit else NoHit
            PLATE -> if (f.size == 1) PlateHit else NoHit
            else -> NoHit
        }
    }
}

@Suppress("unused")
private fun unusedGameFlowRef(g: GameFlow) = g
