package io.embodit.puzzlebox.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.puzzlebox.domain.Layout
import io.embodit.puzzlebox.domain.PieceType
import io.embodit.puzzlebox.ui.board.input.GazeTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The SDK-side resources of the volume: abstract marks (planes for the plate, tab faces, mouth overlay, outline, anchor
 * dot; small translucent cubes for the hole / ghost indicators; the <= 5 mm pad markers) and every collider shape. The
 * wooden things are GLBs (tools/build_models.py). Creating a resource waits on the renderer, so it is all created
 * concurrently on Dispatchers.IO.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var platePlane: MeshResource; private set
    lateinit var tabPlane: MeshResource; private set
    lateinit var dotPlane: MeshResource; private set
    lateinit var dotMat: Material; private set
    lateinit var cellCube: MeshResource; private set
    lateinit var ghostWhite: Material; private set
    lateinit var ghostRed: Material; private set
    lateinit var holeRed: Material; private set
    lateinit var acrylic: Material; private set
    lateinit var hintMat: Map<PieceType, Material>; private set
    lateinit var markerParts: List<GazeTargets.Part>; private set
    lateinit var marker: Material; private set

    lateinit var physics: PhysicsMaterialResource; private set
    lateinit var padShape: ShapeResource; private set
    lateinit var tabShape: ShapeResource; private set
    lateinit var plateShape: ShapeResource; private set
    /** Per block type: one cube shape per canonical cell, grown 1 cm on every side (tray / stand, design hitTargets). */
    lateinit var bigShapes: Map<PieceType, List<ShapeResource>>; private set
    /** Per block type: one cube per cell, exactly the visible cells (blocks in the box). */
    lateinit var exactShapes: Map<PieceType, List<ShapeResource>>; private set

    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }

    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    companion object {
        /** Mouth pad markers (board-input template "标记尺寸"): <= 5 mm round dot, 1.2 mm proud; the pad itself is a whole cell. */
        val MARKER_STYLE = GazeTargets.Style(fraction = 0.005f / Layout.U, thick = 0.0012f, corner = 0.0024f)
        const val GROW = 0.01f
        /** Hint ghost opacity range (design entities3d.ghost, changes c6: <= 0.25, 1.5 Hz pulse). */
        const val HINT_MIN = 0.12f
        const val HINT_MAX = 0.25f
        const val HINT_HZ = 1.5f

        /** The hint ghost's opacity at [ms] (uptime): a 1.5 Hz pulse; steady 0.2 with reduced motion. */
        fun hintOpacity(ms: Long, reduced: Boolean): Float =
            if (reduced) 0.2f else HINT_MIN + (HINT_MAX - HINT_MIN) * (0.5f + 0.5f * kotlin.math.sin(2 * Math.PI.toFloat() * HINT_HZ * (ms % 100_000) / 1000f))

        fun unlit(color: Color, opacity: Float = 1f, tex: TextureResource? = null): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f || tex != null) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                tex?.let { setBaseColorTexture(it) }
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T = r.keep(this)
            val io = Dispatchers.IO
            val U = Layout.U
            val dotTex = async(io) { TextureResource.create(BoardTextures.dot()).keep() }
            val ghostTex = async(io) { TextureResource.create(BoardTextures.ghostEdge()).keep() }
            val big = PieceType.entries.associateWith { t ->
                async(io) { t.cells.map { c -> ShapeResource.createBox(Vector3(U + 2 * GROW, U + 2 * GROW, U + 2 * GROW)).offsetByTranslation(Vector3(c.x * U, c.y * U, c.z * U)).keep() } }
            }
            val exact = PieceType.entries.associateWith { t ->
                async(io) { t.cells.map { c -> ShapeResource.createBox(Vector3(U, U, U)).offsetByTranslation(Vector3(c.x * U, c.y * U, c.z * U)).keep() } }
            }
            listOf(
                async(io) { r.platePlane = MeshResource.createPlane(Layout.PLATE_W, Layout.PLATE_H, 0f).keep() },
                async(io) { r.tabPlane = MeshResource.createPlane(Layout.TAB_W - 0.004f, Layout.TAB_H - 0.004f, 0f).keep() },
                async(io) { r.dotPlane = MeshResource.createPlane(0.012f, 0.012f, 0f).keep() },
                // gate:allow geometry hole / ghost indicator: an abstract translucent unlit cell marker (design entities3d holeGlow / ghost), not a prop
                async(io) { r.cellCube = MeshResource.createBox(Vector3(Layout.CUBE * 0.96f, Layout.CUBE * 0.96f, Layout.CUBE * 0.96f), cornerRadius = 0.002f).keep() },
                async(io) { r.markerParts = GazeTargets.markerMeshes(U, MARKER_STYLE).also { ps -> ps.map { it.mesh }.distinct().forEach { it.keep() } } },
                async(io) {
                    r.marker = PhysicallyBasedMaterial.create().apply { setBaseColor(Color(0xFFE9E4D8).c4(1f)); setRoughness(0.5f); setMetallic(0f) }.keep()
                },
                async(io) {
                    r.acrylic = PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
                        setBaseColor(Color(0xFFDDEBF0).c4(1f)); setOpacity(0.18f); setRoughness(0.08f); setMetallic(0f)
                        setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
                    }.keep()
                },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                async(io) { r.padShape = ShapeResource.createBox(Vector3(U, 0.004f, U)).keep() },
                async(io) { r.tabShape = ShapeResource.createBox(Vector3(Layout.TAB_HIT.x, Layout.TAB_HIT.y, Layout.TAB_HIT.z)).offsetByTranslation(Vector3(Layout.TAB_HIT_OFFSET.x, Layout.TAB_HIT_OFFSET.y, Layout.TAB_HIT_OFFSET.z)).keep() },
                async(io) { r.plateShape = ShapeResource.createBox(Vector3(Layout.PLATE_HIT_W, Layout.PLATE_HIT_H, Layout.PLATE_HIT_T)).keep() },
            ).awaitAll()
            r.bigShapes = big.mapValues { it.value.await() }
            r.exactShapes = exact.mapValues { it.value.await() }
            r.dotMat = unlit(Color.White, tex = dotTex.await()).keep()
            r.ghostWhite = unlit(Color(0xFFFFFFFF), opacity = 0.35f).keep()
            r.ghostRed = unlit(Color(0xFFE5533D), opacity = 0.45f).keep()
            r.holeRed = unlit(Color(0xFF9B1C1C), opacity = 0.55f).keep()
            // M16 / QA K1: the hint ghost is a bright-edged, mostly see-through cell (opacity pulses HINT_MIN..HINT_MAX at 1.5 Hz)
            val edge = ghostTex.await()
            r.hintMat = PieceType.entries.associateWith { t -> unlit(Color(t.color), opacity = HINT_MAX, tex = edge).keep() }
            r
        }
    }
}
