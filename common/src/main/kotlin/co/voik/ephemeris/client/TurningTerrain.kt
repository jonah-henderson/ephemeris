package co.voik.ephemeris.client

import co.voik.ephemeris.sky.ColourCycles
import co.voik.ephemeris.sky.LevelLooks
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.pipeline.UniformType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.oit.OitPipelineSet
import net.minecraft.client.renderer.oit.OitStage
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * The chunk mesh's tinted colours turning round the colour wheel ([ColourCycles.ground], [ColourCycles.water]) — grass,
 * leaves and water, which are baked into the mesh when a section is built and so cannot be repainted per frame.
 *
 * **The terrain is drawn through copies of vanilla's own pipelines** whose vertex shader turns a vertex's colour before
 * the light is laid on it (`terrain_turning.vsh`), and nothing is rebuilt. Each copy differs from vanilla's in that
 * shader and one uniform block, which carries the turn worked out here off the level's whole clock, so a cycle may be
 * longer than the day `GameTime` wraps at. A shader cannot tell a grass vertex from a leaf's, only which layer it is
 * drawn in, so the ground's solid and cutout layers turn together and the translucent layer is the water's.
 *
 * Swapped in by `ChunkSectionLayerMixin` and `ChunkSectionsToRenderMixin`, and only for the level being drawn: a level
 * with nothing turning in its mesh draws through vanilla's own pipelines untouched. A mod that replaces vanilla's chunk
 * renderer (Sodium) never asks, and its terrain keeps still.
 */
object TurningTerrain {

    /** Our copy of [layer]'s pipeline where the level being drawn turns that layer's colours, or null for vanilla's. */
    @JvmStatic
    fun pipelineFor(layer: ChunkSectionLayer, multiDraw: Boolean): RenderPipeline? {
        val cycles = cyclesNow() ?: return null
        val turns = if (layer.translucent()) cycles.water != null else cycles.ground != null
        if (!turns) return null
        return if (multiDraw) MULTIDRAW.getValue(layer) else SEPARATE.getValue(layer)
    }

    /** Our copy of the transparency stage [stage]'s pipeline where the level being drawn turns its water, or [vanilla]. */
    @JvmStatic
    fun oitPipelineFor(vanilla: RenderPipeline, stage: OitStage, multiDraw: Boolean): RenderPipeline {
        if (cyclesNow()?.water == null) return vanilla
        return (if (multiDraw) OIT_MULTIDRAW else OIT_SEPARATE).getPipeline(stage)
    }

    /**
     * Binds the turns to [pass] where it draws any of [layers] through our copies, or through an [override] that is one
     * of ours; vanilla's own pipelines declare no such block and are never handed one.
     */
    @JvmStatic
    fun bindTo(pass: RenderPass, layers: Array<ChunkSectionLayer>, override: RenderPipeline?) {
        val cycles = cyclesNow() ?: return
        val drawsThroughOurs = if (override != null) override in OURS else layers.any { pipelineFor(it, multiDraw = false) != null }
        if (!drawsThroughOurs) return
        val level = Minecraft.getInstance().level ?: return
        val partialTick = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
        val groundTurn = cycles.ground?.turnAt(level.gameTime, partialTick) ?: NOT_TURNED
        val waterTurn = cycles.water?.turnAt(level.gameTime, partialTick) ?: NOT_TURNED
        turning.currentBuffer().map(false, true).use { view ->
            Std140Builder.intoBuffer(view.data()).putVec4(groundTurn, waterTurn, 0.0f, 0.0f)
        }
        pass.setUniform(UNIFORM, turning.currentBuffer())
        boundThisFrame = true
    }

    /**
     * Moves the turns on to the ring's next buffer, once a frame and outside any pass: rotating makes a fence, which may
     * not be made while a pass is open, and [bindTo] is called from inside one. Every pass in a frame writes the same
     * turns, so the passes sharing one buffer agree.
     */
    @JvmStatic
    fun endFrame() {
        if (!boundThisFrame) return
        boundThisFrame = false
        turning.rotate()
    }

    private var boundThisFrame = false

    private fun cyclesNow(): ColourCycles? {
        val level = Minecraft.getInstance().level ?: return null
        val cycles = LevelLooks.of(level.dimension())?.cycles ?: return null
        return cycles.takeIf { it.ground != null || it.water != null }
    }

    private const val NAMESPACE = "ephemeris"
    private const val UNIFORM = "TurningInfo"
    private const val NOT_TURNED = 0.0f
    private const val TURNING_INFO_BYTES = 4 * Float.SIZE_BYTES

    private val VERTEX_SHADER: Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "terrain_turning")

    private val TURNING_LAYOUT: BindGroupLayout = BindGroupLayout.builder()
        .withUniform(UNIFORM, UniformType.UNIFORM_BUFFER)
        .build()

    private val turning: MappableRingBuffer by lazy {
        MappableRingBuffer({ "Ephemeris turning terrain UBO" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, TURNING_INFO_BYTES)
    }

    /**
     * [vanilla] with our vertex shader and the turns' uniform block, and otherwise exactly as vanilla built it: its
     * fragment shader, defines, layouts, blending, depth and vertex formats, copied rather than restated.
     */
    // NeoForge deprecates this constructor for one taking a field only it has; `common` compiles against vanilla.
    @Suppress("DEPRECATION")
    private fun turningCopyOf(vanilla: RenderPipeline, isWater: Boolean): RenderPipeline {
        val colourTargets = vanilla.colorTargetStates.toTypedArray()
        val asVanillaBuiltIt = RenderPipeline.Snippet(
            vanilla.shaders + (ShaderType.VERTEX to VERTEX_SHADER),
            Optional.of(vanilla.shaderDefines),
            Optional.of(vanilla.bindGroupLayouts + TURNING_LAYOUT),
            colourTargets,
            colourTargets.count { it != null },
            Optional.ofNullable(vanilla.depthStencilState),
            Optional.of(vanilla.polygonMode),
            Optional.of(vanilla.isCull),
            vanilla.vertexFormatBindings.toTypedArray(),
            Optional.of(vanilla.primitiveTopology),
            vanilla.pushConstantSize(),
        )
        val builder = RenderPipeline.builder(asVanillaBuiltIt)
            .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "turning/${vanilla.location.path}"))
        if (isWater) builder.withShaderDefine(WATER_DEFINE)
        return builder.build()
    }

    private const val WATER_DEFINE = "TURNS_WATER"

    // Vanilla's pipelines named rather than asked of each layer, since asking is what `ChunkSectionLayerMixin` answers.
    private val SEPARATE: Map<ChunkSectionLayer, RenderPipeline> by lazy {
        mapOf(
            ChunkSectionLayer.SOLID to turningCopyOf(RenderPipelines.SOLID_TERRAIN, isWater = false),
            ChunkSectionLayer.CUTOUT to turningCopyOf(RenderPipelines.CUTOUT_TERRAIN, isWater = false),
            ChunkSectionLayer.TRANSLUCENT to turningCopyOf(RenderPipelines.TRANSLUCENT_TERRAIN, isWater = true),
        )
    }

    private val MULTIDRAW: Map<ChunkSectionLayer, RenderPipeline> by lazy {
        mapOf(
            ChunkSectionLayer.SOLID to turningCopyOf(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, isWater = false),
            ChunkSectionLayer.CUTOUT to turningCopyOf(RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, isWater = false),
            ChunkSectionLayer.TRANSLUCENT to turningCopyOf(RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW, isWater = true),
        )
    }

    private fun oitCopyOf(vanilla: OitPipelineSet) = OitPipelineSet(
        turningCopyOf(vanilla.depthBoundsPipeline(), isWater = true),
        turningCopyOf(vanilla.transmittancePipeline(), isWater = true),
        turningCopyOf(vanilla.accumulatePipeline(), isWater = true),
    )

    private val OIT_SEPARATE: OitPipelineSet by lazy { oitCopyOf(RenderPipelines.OIT_TERRAIN) }
    private val OIT_MULTIDRAW: OitPipelineSet by lazy { oitCopyOf(RenderPipelines.OIT_TERRAIN_MULTIDRAW) }

    private val OURS: Set<RenderPipeline> by lazy {
        SEPARATE.values.toSet() + MULTIDRAW.values + OitStage.entries.flatMap { stage ->
            listOf(OIT_SEPARATE.getPipeline(stage), OIT_MULTIDRAW.getPipeline(stage))
        }
    }
}
