package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.RuntimeLevelLog
import co.voik.ephemeris.Sphere
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.CloudDeck
import co.voik.ephemeris.sky.HorizonFan
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.client.renderer.RenderPipelines
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.Random

/**
 * The one implementation of [SkyCanvas], drawing through Blaze3D. In `common` rather than per loader,
 * because Blaze3D is vanilla's; nothing server-side may reach this class.
 *
 * Geometry is built once into a [GpuBuffer] and a draw is a [RenderPass] whose pipeline carries the blend
 * and depth state, so each shape here is one static buffer drawn under a per-instance transform.
 *
 * Bodies borrow vanilla's `CELESTIAL`, whose `BlendFunction.OVERLAY` is what makes a sun read as light
 * rather than a pasted circle. Stars and cloud decks have pipelines of ours — see [STARFIELD_PIPELINE]
 * and [CLOUD_DECK_PIPELINE] for what each buys.
 */
object Blaze3dSkyCanvas : SkyCanvas {

    /**
     * The namespace the pipelines and shaders are registered under — **the library's own, not a consumer's**.
     *
     * Ephemeris ships these four shaders and nothing else. Every *texture* it draws is vanilla's, so a
     * consumer that supplies none still gets a sun and a moon, and one that supplies its own names them in
     * `Appearance.Sprite.shapes` without this file knowing.
     */
    private const val NAMESPACE = "ephemeris"


    /** A body is one quad: four corners, drawn as two triangles off the shared quad index buffer. */
    private const val QUAD_VERTICES = 4
    private const val QUAD_INDICES = 6

    /**
     * A body's quad, with the sprite's atlas coordinates baked in — and remembered, because a resource
     * reload re-stitches the atlas and a sprite that moved would otherwise keep drawing from where it used
     * to be.
     */
    private data class BodyQuad(val buffer: GpuBuffer, val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
        fun wasBakedFrom(sprite: TextureAtlasSprite): Boolean =
            u0 == sprite.u0 && v0 == sprite.v0 && u1 == sprite.u1 && v1 == sprite.v1
    }

    private val bodyQuads = mutableMapOf<Pair<Identifier, Kept>, BodyQuad>()

    /** A field's geometry and how many indices it takes to draw, keyed by the field that asked for it. */
    private data class Starfield(val buffer: GpuBuffer, val indexCount: Int)

    /**
     * Built star fields, keyed by seed and count so two Ages that asked for the same field share one.
     * Never cleared: a field is one buffer and a player visits a bounded number of Ages, so eviction
     * bookkeeping would cost more than the memory it saves.
     */
    private val starfields = mutableMapOf<Pair<Long, Int>, Starfield>()

    private const val STAR_DISTANCE = 100.0

    /** Vanilla's own half-extents at its own distance, so a star reads as one of vanilla's. */
    private const val MIN_STAR_SIZE = 0.15
    private const val STAR_SIZE_VARIATION = 0.10
    private const val FULL_CIRCLE_RADIANS = 2.0 * Math.PI

    /**
     * Stars vary along a warm→cool axis, each twinkling at its own phase and rate.
     *
     * Both ends sit near white and the dip is shallow, because vanilla's stars are white and undimmed and
     * this is meant to be a colour a second look finds rather than one the sky is made of.
     */
    private val WARM_STAR = Rgba(1.0f, 0.94f, 0.86f)
    private val COOL_STAR = Rgba(0.87f, 0.92f, 1.0f)
    private const val STAR_TWINKLE_DIP = 0.55f
    private const val SLOWEST_TWINKLE_RATE = 0.04f

    /**
     * Rates are whole multiples of [SLOWEST_TWINKLE_RATE] rather than anything in a range, so that one
     * period exists at which every star completes a whole number of cycles and the time can be wrapped.
     * Phases stay continuous, and they are what the eye reads anyway.
     */
    private const val TWINKLE_RATES = 3

    /**
     * The stars have a pipeline of ours rather than borrowing `RenderPipelines.STARS`, which is
     * `POSITION`-only and whose shader writes one flat colour for the whole field — no per-star tint and
     * no twinkle. Blend and depth match vanilla's.
     */
    private val STARFIELD_PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/starfield"))
        .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "starfield"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "starfield"))
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("StarfieldInfo", UniformType.UNIFORM_BUFFER)
        .withColorTargetState(ColorTargetState(BlendFunction.OVERLAY))
        // Culling defaults to on, and `Sphere.tangentQuad` does not promise a winding.
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        .build()

    /** Where the twinkle may wrap without jumping — see [TWINKLE_RATES]. */
    private const val TWINKLE_PERIOD = FULL_CIRCLE_RADIANS / SLOWEST_TWINKLE_RATE

    private val starfieldInfo: MappableRingBuffer by lazy {
        MappableRingBuffer(
            { "Ephemeris starfield UBO" },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE,
            STARFIELD_INFO_BYTES,
        )
    }

    /** One `vec4`, matching `StarfieldInfo` in the shaders. */
    private const val STARFIELD_INFO_BYTES = 4 * Float.SIZE_BYTES

    /**
     * Needs no registration: Blaze3D compiles a pipeline on first use, reading shaders through
     * `ShaderManager`, which scans `shaders/` across every namespace. Registering would only buy preloading.
     *
     * Depth is written, or looking down through the upper deck shows everything below it.
     */
    private val CLOUD_DECK_PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/cloud_deck"))
        .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "cloud_deck"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "cloud_deck"))
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("DeckInfo", UniformType.UNIFORM_BUFFER)
        // The picture the deck is cut from. Declared even though a solid deck ignores it — one pipeline
        // that sometimes skips a sample beats two that each carry their own copy of the roil.
        .withSampler("Sampler0")
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState.DEFAULT)
        // The viewer stands inside the slab as often as outside it, so neither face may be dropped.
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        .build()

    /**
     * For a body asking to **cover** rather than add — `Blending.COVERS`.
     *
     * Vanilla's `CELESTIAL` blends additively, so an overlap brightens instead of hiding; this is the same
     * pipeline with a translucent blend, which lets one body hide another behind it.
     *
     * **It needs a sprite with an alpha channel, and vanilla's have none** — theirs are indexed with no
     * `tRNS`, two thirds opaque near-black, which additive blending never reveals and this paints straight
     * over the sky. That is why nothing reaches this by default; see `Blending`.
     */
    private val OCCLUDING_BODY_PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/occluding_body"))
        .withVertexShader(Identifier.withDefaultNamespace("core/position_tex"))
        .withFragmentShader(Identifier.withDefaultNamespace("core/position_tex"))
        .withSampler("Sampler0")
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
        .build()

    /**
     * The curtain's own pipeline. Additive, like the stars, because an aurora is light laid on the sky
     * rather than a sheet hung in front of it; and depth is left alone, so it sits in the sky pass and the
     * terrain drawn afterwards hides whatever stands in front of it.
     */
    private val AURORA_PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/aurora"))
        .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "aurora"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "aurora"))
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("AuroraInfo", UniformType.UNIFORM_BUFFER)
        // The colour ramp, built per ramp rather than shipped — see [rampOf].
        .withSampler("Sampler0")
        .withColorTargetState(ColorTargetState(BlendFunction.OVERLAY))
        // The viewer is inside the band, and the grid below promises no winding.
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
        .build()

    /**
     * The curtain's canvas: a band of sky, generous in both directions, with the curtain *carved out of it*
     * by the fragment shader.
     *
     * **Generous geometry and a shader-carved silhouette**, which is the whole reason this is affordable.
     * The alternative is a mesh that is the curtain — folds, taper, hem and all — rebuilt as it moves, which
     * is a vertex buffer written every frame for a shape a handful of sines already describe. Here the mesh
     * never changes and every fold is arithmetic.
     *
     * Wider than the widest curtain and taller than the tallest, so `breadth` and `height` crop rather than
     * scale: a band that only ever showed as much sky as it was asked for would have nothing to taper into.
     */
    private const val AURORA_DISTANCE = 100.0f

    /** Either side of the bearing, in degrees. Just short of a half-turn, so the ends can taper. */
    private const val AURORA_HALF_SWEEP = 95.0f

    /** The band of sky it may occupy, in degrees above the horizon. */
    private const val AURORA_LOWEST = 6.0f
    private const val AURORA_HIGHEST = 84.0f

    /**
     * How finely the band is divided. Enough that the arc reads as a curve rather than a chord, and no more:
     * the detail is in the fragment stage, so these buy geometry and not appearance.
     */
    private const val AURORA_ACROSS = 64
    private const val AURORA_UP = 12
    private const val AURORA_QUADS = AURORA_ACROSS * AURORA_UP
    private const val AURORA_INDICES = AURORA_QUADS * QUAD_INDICES

    /**
     * Where the fold may wrap without jumping.
     *
     * `aurora.fsh` moves its sines at 0.13, 0.21, 0.09 and 0.05 of the drifted time — all whole multiples of
     * 0.01 — and `200π` completes a whole number of turns for every such multiple. Adding a coefficient that
     * is not one breaks this, which is why they are all hundredths.
     */
    private const val AURORA_FOLD_PERIOD = 200.0 * Math.PI

    /** How fast the fold travels. Slow: an aurora moves at the pace of something very far away. */
    private const val AURORA_DRIFT = 0.05

    /** How far the curtain's middle wanders from the band's, as a share of the band. */
    private const val AURORA_WANDER = 1.0f

    /** How fine the vertical rays are. Higher is more of them. */
    private const val AURORA_RAY_FINENESS = 220.0f

    /**
     * How far apart two curtains are dealt in phase, in radians.
     *
     * Not a fraction of a turn, so no two of them ever come round to breathe together however long anybody
     * watches.
     */
    private const val CURTAINS_APART = 2.1

    /** The rates the curtains swell at. Coprime over [AURORA_FOLD_PERIOD], and both hundredths — see it. */
    private const val SLOW_SWELL = 0.03
    private const val SLOWER_SWELL = 0.07

    /** Above 1, so a curtain spends more of its time faint than bright and the sky is rarely full. */
    private const val SWELL_PEAKINESS = 1.7

    /** How far apart their bearings lean, in degrees. Near-parallel, which is what real arcs are. */
    private const val BETWEEN_CURTAINS = 11.0f

    /** How far up and down the band several curtains are spread, as a share of it. */
    private const val CURTAINS_SPREAD = 0.34f

    /** How much smaller a later curtain may be than the first, so they are not clones. */
    private const val CURTAIN_VARIANCE = 0.35f

    /** An irrational-ish step, so the variation does not repeat every few curtains. */
    private const val CURTAIN_STRIDE = 0.618f

    /**
     * How many steps the ramp is baked into.
     *
     * **Interpolated here rather than by the sampler**, which is what makes any number of colours free: the
     * stops are blended into this many pixels on the way in, so the shader takes one fetch and never loops,
     * and nothing depends on how a `GpuSampler` was configured. At this width one step is a fraction of a
     * degree of sky, which is past anything an eye resolves.
     */
    private const val RAMP_STEPS = 128

    /**
     * Ramps already baked, keyed by the colours they were baked from.
     *
     * Never cleared, on the same argument as [starfields]: a ramp is one small texture, a player visits a
     * bounded number of levels, and eviction bookkeeping would cost more than the memory it saves. Unlike
     * [bodyQuads] there is nothing here to go stale — a ramp is built from the level's own data rather than
     * cut from an atlas that a resource reload re-stitches.
     */
    private val ramps = mutableMapOf<List<Rgba>, DynamicTexture>()

    private val auroraInfo: MappableRingBuffer by lazy {
        MappableRingBuffer(
            { "Ephemeris aurora UBO" },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE,
            AURORA_INFO_BYTES,
        )
    }

    /** Two `vec4`s, matching `AuroraInfo` in the shaders. */
    private const val AURORA_INFO_BYTES = 2 * 4 * Float.SIZE_BYTES

    private var curtainBuffer: GpuBuffer? = null

    /** Said once. A frame that cannot draw is very likely every frame, and a log per frame helps nobody. */
    private var saidTheTargetWasEmpty = false

    /** Half the slab's width. Beyond this the deck simply ends, which is why it is walled. */
    private const val DECK_RADIUS = 512.0f

    /** See the note in [drawCloudDeck]: half a block, to keep a deck out of a block face's plane. */
    private const val DECK_LIFT = 0.5

    /**
     * Where the drift may wrap without jumping: the roil's four sines have time coefficients 1, 0.9, 1.4
     * and 0.7, and `20π` is the smallest period all of them complete together.
     */
    private const val ROIL_PERIOD = 20.0 * Math.PI

    /** Six faces, so the slab is closed rather than a pair of floating sheets. */
    private const val SLAB_QUADS = 6
    private const val SLAB_INDICES = SLAB_QUADS * QUAD_INDICES

    /** Vanilla's own cloud shading: the top lit, the underside darkest, the walls between. */
    private const val TOP_BRIGHTNESS = 1.0f
    private const val BOTTOM_BRIGHTNESS = 0.72f
    private const val SIDE_BRIGHTNESS = 0.84f

    private val deckInfo: MappableRingBuffer by lazy {
        MappableRingBuffer(
            { "Ephemeris cloud deck UBO" },
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE,
            DECK_INFO_BYTES,
        )
    }

    /** Four `vec4`s, matching `DeckInfo` in the shaders. */
    private const val DECK_INFO_BYTES = 4 * 4 * Float.SIZE_BYTES

    private var slabBuffer: GpuBuffer? = null

    override fun drawHorizonGlow(bearingDegrees: Float, tint: Rgba) {
        if (tint.alpha <= FAINTEST_GLOW) return

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        // Vanilla's own sequence, with one substitution: it stands the fan up out of the ground and then
        // turns it to face the light. The turn is `HorizonFan.turnFor`, which is *not* the bearing — see
        // there for why standing the fan up carries its centre to the far side, and what it looks like when
        // that is missed.
        modelViewStack.rotate(Quaternionf().rotateX(Math.toRadians(HorizonFan.STAND_IT_UP.toDouble()).toFloat()))
        modelViewStack.rotate(
            Quaternionf().rotateZ(Math.toRadians(HorizonFan.turnFor(bearingDegrees).toDouble()).toFloat()),
        )
        // Vanilla flattens the fan by its own alpha so a weak glow is a thin band rather than a faint wide
        // one. Keeping that means a distant sun's light hugs the horizon instead of washing the whole sky.
        modelViewStack.scale(1.0f, 1.0f, tint.alpha)

        val transforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelViewStack,
            Vector4f(tint.red, tint.green, tint.blue, tint.alpha),
            Vector3f(),
            Matrix4f(),
        )

        renderPass("Ephemeris horizon glow")?.use { pass ->
            pass.setPipeline(RenderPipelines.SUNRISE_SUNSET)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setVertexBuffer(0, horizonFan())
            pass.draw(0, GLOW_FAN_VERTICES)
        }
        modelViewStack.popMatrix()
    }

    /**
     * The glow's mesh: a bright point overhead of the horizon with a ring of transparent vertices around it,
     * as a triangle fan — vanilla's own shape, rebuilt because its buffer is private to its `SkyRenderer`.
     *
     * Built once and kept. The colour lives in the transform uniform rather than the vertices, which is what
     * lets one buffer serve every sun in the sky.
     */
    private fun horizonFan(): GpuBuffer = glowFan ?: buildGlowFan().also { glowFan = it }

    private fun buildGlowFan(): GpuBuffer {
        val vertexSize = DefaultVertexFormat.POSITION_COLOR.vertexSize
        ByteBufferBuilder.exactlySized(GLOW_FAN_VERTICES * vertexSize).use { bytes ->
            val builder = BufferBuilder(bytes, VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR)
            builder.addVertex(0.0f, GLOW_CENTRE_HEIGHT, 0.0f).setColor(-1)
            for (step in 0..GLOW_FAN_STEPS) {
                val angle = step * (Math.PI * 2.0).toFloat() / GLOW_FAN_STEPS
                val sin = Math.sin(angle.toDouble()).toFloat()
                val cos = Math.cos(angle.toDouble()).toFloat()
                builder.addVertex(sin * GLOW_RADIUS, cos * GLOW_RADIUS, -cos * GLOW_DEPTH).setColor(0x00FFFFFF)
            }
            builder.buildOrThrow().use { mesh ->
                return RenderSystem.getDevice()
                    .createBuffer({ "Ephemeris horizon glow fan" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
            }
        }
    }

    private var glowFan: GpuBuffer? = null

    /** Vanilla's own numbers for the fan, which is why they are not round. */
    private const val GLOW_FAN_STEPS = 16
    private const val GLOW_FAN_VERTICES = 18
    private const val GLOW_CENTRE_HEIGHT = 100.0f
    private const val GLOW_RADIUS = 120.0f
    private const val GLOW_DEPTH = 40.0f


    /** Below this a glow is not worth a draw call; vanilla declines at the same point. */
    private const val FAINTEST_GLOW = 0.001f

    override fun drawBody(
        shape: Identifier,
        orientation: Quaternionf,
        distance: Float,
        angularSize: Float,
        tint: Rgba,
        veil: Rgba,
        emitsOwnLight: Boolean,
    ) {
        val kept = keptOf(shape)
        // **A cropped body is drawn twice, because its two parts are different things.** Vanilla paints a
        // glow around its moon, radially, out to three times the disc's own radius — light, which adds. The
        // disc is a body, which covers. Cropping alone would throw the glow away and leave a small hard
        // moon; adding alone cannot hide a sun behind it.
        //
        // The glow goes first and the disc lands on top, so the disc's own brightness is not counted twice
        // and whatever was behind it — a sun, another moon — is hidden by the pass that covers.
        if (!emitsOwnLight && kept != WHOLE_SPRITE) {
            drawOneQuad(shape, WHOLE_SPRITE, orientation, distance, angularSize, tint.dimmed(GLOWS), true)
        }
        drawOneQuad(shape, kept, orientation, distance, angularSize, tint, emitsOwnLight)
        // **And the air's own light on top of it**, which is what pales a covering body by day without
        // making it see-through. A plain quad the body's size, added: `RenderPipelines.STARS` is exactly
        // that shader — a position in and `ColorModulator` out — so no pipeline of ours is needed.
        if (veil.alpha > VEIL_WORTH_DRAWING) {
            drawVeilQuad(kept, orientation, distance, angularSize, veil)
        }
    }

    /** Below this the air over a body is not worth a draw of its own. */
    private const val VEIL_WORTH_DRAWING = 0.002f

    private fun drawVeilQuad(
        kept: Kept,
        orientation: Quaternionf,
        distance: Float,
        angularSize: Float,
        veil: Rgba,
    ) {
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        modelViewStack.rotate(orientation)
        modelViewStack.translate(0.0f, distance, 0.0f)
        modelViewStack.scale(angularSize, 1.0f, angularSize)

        val transforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelViewStack,
            Vector4f(veil.red, veil.green, veil.blue, veil.alpha),
            Vector3f(),
            Matrix4f(),
        )
        val quadIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)

        renderPass("Ephemeris sky body veil")?.use { pass ->
            pass.setPipeline(RenderPipelines.STARS)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setVertexBuffer(0, veilQuadOf(kept))
            pass.setIndexBuffer(quadIndices.getBuffer(QUAD_INDICES), quadIndices.type())
            pass.drawIndexed(0, 0, QUAD_INDICES, 1)
        }

        modelViewStack.popMatrix()
    }

    /**
     * A textureless quad the size of a body's, for the light the air lays over it. Two ever exist — one for
     * a whole sprite and one for vanilla's cropped moon — and neither depends on the atlas, so unlike
     * [bodyQuadOf] these are never rebuilt.
     */
    private val veilQuads = mutableMapOf<Kept, GpuBuffer>()

    private fun veilQuadOf(kept: Kept): GpuBuffer = veilQuads.getOrPut(kept) {
        val reach = kept.to - kept.from
        val format = DefaultVertexFormat.POSITION
        ByteBufferBuilder.exactlySized(QUAD_VERTICES * format.vertexSize).use { bytes ->
            val builder = BufferBuilder(bytes, VertexFormat.Mode.QUADS, format)
            builder.addVertex(-reach, 0.0f, -reach)
            builder.addVertex(reach, 0.0f, -reach)
            builder.addVertex(reach, 0.0f, reach)
            builder.addVertex(-reach, 0.0f, reach)
            builder.buildOrThrow().use { mesh ->
                RenderSystem.getDevice()
                    .createBuffer({ "Ephemeris sky veil quad" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
            }
        }
    }

    /** How much of a cropped body's glow is added around it. Its own dimming, as a luminous body gets. */
    private const val GLOWS = 0.55f

    private fun drawOneQuad(
        shape: Identifier,
        kept: Kept,
        orientation: Quaternionf,
        distance: Float,
        angularSize: Float,
        tint: Rgba,
        emitsOwnLight: Boolean,
    ) {
        val atlas = celestialsAtlas()
        val quad = bodyQuadOf(atlas, shape, kept)
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        // Vanilla's own sequence for its sun. The quad lies in the XZ plane, so the scale leaves Y alone.
        modelViewStack.rotate(orientation)
        modelViewStack.translate(0.0f, distance, 0.0f)
        modelViewStack.scale(angularSize, 1.0f, angularSize)

        val transforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelViewStack,
            Vector4f(tint.red, tint.green, tint.blue, tint.alpha),
            Vector3f(),
            Matrix4f(),
        )
        val quadIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)

        renderPass("Ephemeris sky body")?.use { pass ->
            pass.setPipeline(pipelineFor(shape, emitsOwnLight))
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.bindTexture("Sampler0", atlas.textureView, atlas.sampler)
            pass.setVertexBuffer(0, quad.buffer)
            pass.setIndexBuffer(quadIndices.getBuffer(QUAD_INDICES), quadIndices.type())
            pass.drawIndexed(0, 0, QUAD_INDICES, 1)
        }

        modelViewStack.popMatrix()
    }

    /**
     * Which pipeline draws [shape].
     *
     * Adding needs nothing special — dark contributes nothing to a sum, which is exactly why vanilla's
     * sprites can get away with having no alpha. Covering is drawn plainly, the sky painted around vanilla's
     * moon having already been cropped away by [keptOf] rather than shaded away here. There is no cut
     * *shader*: cropping is exact where a luminance test has to guess, and it gets the new moon right.
     *
     * A texture that needs a rule of its own — a chroma key, a mask in another channel, a rim that should
     * glow — registers a pipeline through [SpriteCuts].
     */
    private fun pipelineFor(shape: Identifier, emitsOwnLight: Boolean): RenderPipeline {
        if (emitsOwnLight) return RenderPipelines.CELESTIAL
        return SpriteCuts.of(shape) ?: OCCLUDING_BODY_PIPELINE
    }

    override fun drawStarfield(seed: Long, count: Int, orientation: Quaternionf, brightness: Float, timeTicks: Long) {
        if (count <= 0) return
        val field = starfieldOf(seed, count)
        if (field.indexCount == 0) return

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        modelViewStack.rotate(orientation)

        // The field's overall brightness; each star's own tint and twinkle ride on its vertices.
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelViewStack,
            Vector4f(brightness, brightness, brightness, brightness),
            Vector3f(),
            Matrix4f(),
        )
        writeStarfieldInfo(timeTicks)
        val quadIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)

        renderPass("Ephemeris sky stars")?.use { pass ->
            pass.setPipeline(STARFIELD_PIPELINE)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("StarfieldInfo", starfieldInfo.currentBuffer())
            pass.setVertexBuffer(0, field.buffer)
            pass.setIndexBuffer(quadIndices.getBuffer(field.indexCount), quadIndices.type())
            pass.drawIndexed(0, 0, field.indexCount, 1)
        }
        starfieldInfo.rotate()

        modelViewStack.popMatrix()
    }

    private fun writeStarfieldInfo(timeTicks: Long) {
        RenderSystem.getDevice().createCommandEncoder().mapBuffer(starfieldInfo.currentBuffer(), false, true)
            .use { view ->
                Std140Builder.intoBuffer(view.data())
                    .putVec4(wrapped(timeTicks.toDouble(), TWINKLE_PERIOD), STAR_TWINKLE_DIP, 0.0f, 0.0f)
            }
    }

    override fun drawCloudDeck(deck: CloudDeck, eye: Vec3, timeTicks: Float) {
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        // Half a block up: a deck at a whole Y is coplanar with that block's face and z-fights as you move.
        modelViewStack.translate(0.0f, (deck.height + DECK_LIFT - eye.y).toFloat(), 0.0f)
        modelViewStack.scale(DECK_RADIUS, deck.halfThickness, DECK_RADIUS)

        val transforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelViewStack,
            Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
            Vector3f(),
            Matrix4f(),
        )
        writeDeckInfo(deck, eye, timeTicks)
        val quadIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)

        // **Bound whether or not it is read.** A pipeline declaring a sampler needs one, so a solid deck
        // binds vanilla's picture too and the shader is told to ignore it — which is cheaper than a second
        // pipeline and a second copy of the roil to keep in step with this one.
        val picture = cloudTexture(deck.texture ?: CloudDeck.VANILLA_CLOUDS) ?: return
        cloudPass()?.use { pass ->
            pass.setPipeline(CLOUD_DECK_PIPELINE)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("DeckInfo", deckInfo.currentBuffer())
            pass.bindTexture("Sampler0", picture.textureView, picture.sampler)
            pass.setVertexBuffer(0, slab())
            pass.setIndexBuffer(quadIndices.getBuffer(SLAB_INDICES), quadIndices.type())
            pass.drawIndexed(0, 0, SLAB_INDICES, 1)
        }
        // Per deck, not per frame: each draw needs its own copy of the uniforms to survive until it runs.
        deckInfo.rotate()

        modelViewStack.popMatrix()
    }

    /**
     * Every curtain this aurora hangs, each on its own clock.
     *
     * **They come and go independently**, which is the whole of why an aurora is a count rather than one
     * wide band: a real display is several arcs that brighten and die out of step with each other, so the
     * sky fills and empties through a night. One curtain held all night reads as scenery.
     *
     * Each also gets its own phase, lift, height and breadth, so what is drawn several times is never the
     * same curtain twice.
     */
    override fun drawAurora(aurora: Aurora, strength: Float, timeTicks: Float) {
        if (strength <= NOTHING_TO_DRAW) return
        val ramp = rampOf(aurora.ramp)
        val drifted = wrapped(timeTicks.toDouble() * AURORA_DRIFT, AURORA_FOLD_PERIOD)
        val many = aurora.curtains.coerceIn(1, Aurora.MOST_CURTAINS)
        for (curtain in 0..<many) {
            val showing = strength * swellOf(curtain, drifted)
            if (showing <= NOTHING_TO_DRAW) continue
            drawOneCurtain(aurora, curtain, many, showing, drifted, ramp)
        }
    }

    /**
     * How far this curtain has swelled just now, `0..1` — nought for one that has died away entirely.
     *
     * Two slow waves at coprime rates, raised to a power so it spends more of its time faint than bright:
     * a sky where every curtain sits at half strength is a sky where nothing is happening. The phase offset
     * is not a fraction of a turn, so no two curtains ever come to breathe together.
     */
    private fun swellOf(curtain: Int, drifted: Float): Float {
        val phase = curtain * CURTAINS_APART
        val slow = Math.sin(drifted * SLOW_SWELL + phase)
        val slower = Math.sin(drifted * SLOWER_SWELL - phase * 1.7)
        val together = ((slow * 0.6 + slower * 0.4) + 1.0) / 2.0
        return Math.pow(together, SWELL_PEAKINESS).toFloat()
    }

    /** One curtain of several, with everything that makes it its own. */
    private fun drawOneCurtain(
        aurora: Aurora,
        curtain: Int,
        many: Int,
        showing: Float,
        drifted: Float,
        ramp: DynamicTexture,
    ) {
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        // **Negated, because a bearing and a turn about Y count opposite ways.** The band is built centred
        // on north, and JOML's `rotateY` carries north toward the *west* as its angle grows where a bearing
        // counts clockwise toward the east. Rotating by the bearing itself puts a north-crossing curtain in
        // the south-west and looks, from inside, like a curtain that is merely somewhere else.
        // Each curtain crosses at its own slight angle to the last, so several arcs do not read as one
        // thick one — real ones are near-parallel rather than parallel.
        val leaning = aurora.bearingDegrees + curtain * BETWEEN_CURTAINS
        modelViewStack.rotate(Quaternionf().rotateY(Math.toRadians(-leaning.toDouble()).toFloat()))

        val transforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelViewStack,
            Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
            Vector3f(),
            Matrix4f(),
        )
        writeAuroraInfo(aurora, curtain, many, showing, drifted)
        val quadIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)

        val pass = renderPass("Ephemeris aurora")
        // **A null pass draws nothing and says nothing**, which is the one silent failure left in this
        // path: every other way an aurora can fail to appear is a number somebody can ask for.
        if (pass == null && !saidTheTargetWasEmpty) {
            saidTheTargetWasEmpty = true
            RuntimeLevelLog.warn("An aurora had no colour attachment to draw onto, so none was drawn")
        }
        pass?.use { pass ->
            pass.setPipeline(AURORA_PIPELINE)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("AuroraInfo", auroraInfo.currentBuffer())
            pass.bindTexture("Sampler0", ramp.textureView, ramp.sampler)
            pass.setVertexBuffer(0, curtain())
            pass.setIndexBuffer(quadIndices.getBuffer(AURORA_INDICES), quadIndices.type())
            pass.drawIndexed(0, 0, AURORA_INDICES, 1)
        }
        auroraInfo.rotate()

        modelViewStack.popMatrix()
    }

    /**
     * One curtain's parameters, laid out to match `AuroraInfo` in the shaders.
     *
     * The per-curtain variation is spent here rather than in the mesh, which never changes: a different
     * phase, a different lift up the band, and a height and breadth jittered off its own index. Nothing is
     * random — two clients drawing the same instant draw the same sky.
     */
    private fun writeAuroraInfo(aurora: Aurora, curtain: Int, many: Int, showing: Float, drifted: Float) {
        val phase = curtain * CURTAINS_APART.toFloat()
        // Spread up the band, so several arcs stand at different heights instead of on top of each other.
        val lift = if (many == 1) 0.0f else (curtain.toFloat() / (many - 1) - 0.5f) * CURTAINS_SPREAD
        val varied = 1.0f - CURTAIN_VARIANCE * ((curtain * CURTAIN_STRIDE) % 1.0f)
        RenderSystem.getDevice().createCommandEncoder().mapBuffer(auroraInfo.currentBuffer(), false, true)
            .use { view ->
                Std140Builder.intoBuffer(view.data())
                    .putVec4(
                        showing,
                        drifted,
                        (aurora.breadth * varied).coerceIn(0.0f, 1.0f),
                        (aurora.height * varied).coerceIn(0.0f, 1.0f),
                    )
                    .putVec4(AURORA_WANDER, AURORA_RAY_FINENESS, phase, lift)
            }
    }

    /** This ramp as a texture, baked on first use and kept. */
    private fun rampOf(colours: List<Rgba>): DynamicTexture = ramps.getOrPut(colours) { bakeRamp(colours) }

    /**
     * The ramp as one row of pixels, **crown at the left**, with the stops already blended between.
     *
     * The order is the one the colours were written in, all the way from the sentence to the fetch, so the
     * only place it is reversed is the one line in `aurora.fsh` that turns "how far up the curtain" into
     * "how far down the ramp" — and that line says so.
     */
    private fun bakeRamp(colours: List<Rgba>): DynamicTexture {
        val image = NativeImage(RAMP_STEPS, 1, false)
        for (step in 0..<RAMP_STEPS) {
            val down = step.toFloat() / (RAMP_STEPS - 1).toFloat()
            image.setPixelABGR(step, 0, packedAbgr(rampAt(colours, down)))
        }
        return DynamicTexture({ "Ephemeris aurora ramp" }, image).also { it.upload() }
    }

    /** The colour [down] of the way from crown to hem, `0..1`. */
    private fun rampAt(colours: List<Rgba>, down: Float): Rgba {
        if (colours.size == 1) return colours.first()
        val scaled = down * (colours.size - 1)
        val above = scaled.toInt().coerceIn(0, colours.size - 2)
        return colours[above].lerp(colours[above + 1], scaled - above)
    }

    /** `NativeImage`'s own channel order, which is not [Rgba.packed]'s. */
    private fun packedAbgr(colour: Rgba): Int {
        fun channel(value: Float) = (value.coerceIn(0.0f, 1.0f) * FULL_CHANNEL).toInt()
        return (channel(colour.alpha) shl 24) or (channel(colour.blue) shl 16) or
            (channel(colour.green) shl 8) or channel(colour.red)
    }

    private const val FULL_CHANNEL = 255.0f

    private const val NOTHING_TO_DRAW = 0.0f

    /** The band of sky the curtain is carved out of, built once and kept. */
    private fun curtain(): GpuBuffer = curtainBuffer ?: buildCurtain().also { curtainBuffer = it }

    /**
     * A grid of quads laid on the sky sphere, spanning [AURORA_HALF_SWEEP] either side of north and
     * [AURORA_LOWEST] to [AURORA_HIGHEST] above the horizon.
     *
     * Each vertex carries where on the band it is rather than where in the world — `0..1` across and `0..1`
     * up — because that is what the fragment stage reasons in, and because it makes the mesh independent of
     * every number the curtain can be asked for.
     */
    private fun buildCurtain(): GpuBuffer {
        val format = DefaultVertexFormat.POSITION_TEX
        return ByteBufferBuilder.exactlySized(AURORA_QUADS * QUAD_VERTICES * format.vertexSize).use { bytes ->
            val builder = BufferBuilder(bytes, VertexFormat.Mode.QUADS, format)

            fun corner(across: Int, up: Int) {
                val alongBand = across.toFloat() / AURORA_ACROSS
                val upBand = up.toFloat() / AURORA_UP
                val azimuth = Math.toRadians(((alongBand - 0.5f) * 2.0f * AURORA_HALF_SWEEP).toDouble())
                val elevation =
                    Math.toRadians((AURORA_LOWEST + upBand * (AURORA_HIGHEST - AURORA_LOWEST)).toDouble())
                val outward = Math.cos(elevation) * AURORA_DISTANCE
                builder.addVertex(
                    (Math.sin(azimuth) * outward).toFloat(),
                    (Math.sin(elevation) * AURORA_DISTANCE).toFloat(),
                    // North is `-Z`, which is where an azimuth of nought points.
                    (-Math.cos(azimuth) * outward).toFloat(),
                ).setUv(alongBand, upBand)
            }

            for (across in 0..<AURORA_ACROSS) {
                for (up in 0..<AURORA_UP) {
                    corner(across, up)
                    corner(across + 1, up)
                    corner(across + 1, up + 1)
                    corner(across, up + 1)
                }
            }

            builder.buildOrThrow().use { mesh ->
                RenderSystem.getDevice()
                    .createBuffer({ "Ephemeris aurora band" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
            }
        }
    }

    /** The deck's parameters, laid out to match `DeckInfo` in the shaders. */
    private fun writeDeckInfo(deck: CloudDeck, eye: Vec3, timeTicks: Float) {
        RenderSystem.getDevice().createCommandEncoder().mapBuffer(deckInfo.currentBuffer(), false, true).use { view ->
            Std140Builder.intoBuffer(view.data())
                .putVec4(deck.low.red, deck.low.green, deck.low.blue, deck.low.alpha)
                .putVec4(deck.high.red, deck.high.green, deck.high.blue, deck.high.alpha)
                .putVec4(
                    (eye.x + deck.noiseOffsetX).toFloat(),
                    (eye.z + deck.noiseOffsetZ).toFloat(),
                    driftedTime(timeTicks, deck.driftSpeed),
                    deck.contrast,
                )
                .putVec4(
                    DECK_RADIUS,
                    CloudDeck.CELL_BLOCKS,
                    if (deck.texture == null) 0.0f else 1.0f,
                    cloudScroll(timeTicks),
                )
        }
    }

    /**
     * The picture a deck is cut from, or null if it has not loaded.
     *
     * Vanilla's texture manager owns it, so a resource pack that retextures the overworld's clouds
     * retextures every deck cut from them — which is the right answer and costs nothing.
     */
    private fun cloudTexture(texture: Identifier) =
        Minecraft.getInstance().textureManager.getTexture(texture)

    /**
     * How far the cloud picture has slid, in blocks, wrapped so the float never grows coarse.
     *
     * Vanilla's own rate. Kept separate from the roil's drift on purpose: one moves the *clouds* and the
     * other stirs their *tone*, and a deck reading one number for both could not have a still sky with a
     * churning surface, or racing clouds with an even one.
     */
    private fun cloudScroll(timeTicks: Float): Float =
        wrapped(timeTicks.toDouble() * VANILLA_BLOCKS_PER_TICK, SCROLL_WRAP)

    /** Vanilla's `BLOCKS_PER_SECOND = 0.6`, per tick. */
    private const val VANILLA_BLOCKS_PER_TICK = 0.6 / 20.0

    /** A whole number of vanilla cells, so the picture wraps where it repeats and the seam is invisible. */
    private const val SCROLL_WRAP = 256.0 * 12.0

    /** How far the roil has drifted, wrapped at [ROIL_PERIOD]. */
    private fun driftedTime(timeTicks: Float, driftSpeed: Float): Float =
        wrapped(timeTicks.toDouble() * driftSpeed, ROIL_PERIOD)

    /**
     * [value] brought back into `0..period`. The shaders read time as a float and game time does not stop,
     * so an unwrapped one eventually quantises the animation into visible steps.
     */
    private fun wrapped(value: Double, period: Double): Float =
        (value - Math.floor(value / period) * period).toFloat()

    /** A pass onto the main render target, which is where the sky pass is already drawing. */
    private fun renderPass(label: String): RenderPass? = passOnto(label, Minecraft.getInstance().mainRenderTarget)

    /** The target vanilla's clouds use, which has its own when the setting calls for one. */
    private fun cloudPass(): RenderPass? {
        val minecraft = Minecraft.getInstance()
        return passOnto("Ephemeris cloud deck", minecraft.levelRenderer.cloudsTarget ?: minecraft.mainRenderTarget)
    }

    /** Null when the target has no colour attachment, which nothing can be drawn into. */
    private fun passOnto(label: String, target: RenderTarget): RenderPass? {
        val colorAttachment = target.colorTextureView ?: return null
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { label },
            colorAttachment,
            OptionalInt.empty(),
            target.depthTextureView,
            OptionalDouble.empty(),
        )
    }

    private fun celestialsAtlas(): TextureAtlas =
        Minecraft.getInstance().atlasManager.getAtlasOrThrow(AtlasIds.CELESTIALS)

    /**
     * This shape's quad, built on first use and rebuilt if the atlas has re-stitched since.
     */
    private fun bodyQuadOf(atlas: TextureAtlas, shape: Identifier, kept: Kept): BodyQuad {
        val sprite = atlas.getSprite(shape)
        val cached = bodyQuads[shape to kept]
        if (cached != null && cached.wasBakedFrom(sprite)) return cached

        cached?.buffer?.close()
        val u0 = Mth.lerp(kept.from, sprite.u0, sprite.u1)
        val u1 = Mth.lerp(kept.to, sprite.u0, sprite.u1)
        val v0 = Mth.lerp(kept.from, sprite.v0, sprite.v1)
        val v1 = Mth.lerp(kept.to, sprite.v0, sprite.v1)
        // The quad shrinks with the window, so cropping changes what is drawn and not how big it looks:
        // `angularSize` still means what it meant when the whole sprite was drawn.
        val reach = kept.to - kept.from

        val format = DefaultVertexFormat.POSITION_TEX
        val built = ByteBufferBuilder.exactlySized(QUAD_VERTICES * format.vertexSize).use { bytes ->
            val builder = BufferBuilder(bytes, VertexFormat.Mode.QUADS, format)
            // Vanilla's own corner and UV order, so a body on a one-day orbit is indistinguishable from
            // vanilla's sun rather than mirrored or upside down.
            builder.addVertex(-reach, 0.0f, -reach).setUv(u0, v0)
            builder.addVertex(reach, 0.0f, -reach).setUv(u1, v0)
            builder.addVertex(reach, 0.0f, reach).setUv(u1, v1)
            builder.addVertex(-reach, 0.0f, reach).setUv(u0, v1)
            builder.buildOrThrow().use { mesh ->
                RenderSystem.getDevice().createBuffer({ "Ephemeris sky body quad" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
            }
        }
        return BodyQuad(built, sprite.u0, sprite.v0, sprite.u1, sprite.v1)
            .also { bodyQuads[shape to kept] = it }
    }

    /**
     * Which part of [shape] is the body — **all of it, unless we know better**.
     *
     * Vanilla's moon sprites have the sky painted around them: a 32 by 32 picture whose moon occupies the
     * middle 8 by 8 and whose surround is a dark blue night-sky gradient. That is invisible drawn additively,
     * where dark adds nothing, and an enormous dark square drawn covering. Cropping to the middle is exact —
     * every phase, including the new moon's faint disc, lies inside it, and nothing else does — where any
     * test on the *colours* has to guess where scenery stops and the body starts, and gets the new moon
     * wrong.
     *
     * **Vanilla's sun is deliberately not cropped.** What surrounds it is not sky but its own glow, a yellow
     * ramp reaching the sprite's edges, and cropping that would throw the corona away.
     *
     * A consumer's own texture is never cropped either: it is presumed to be drawn as it wants to be seen.
     */
    private fun keptOf(shape: Identifier): Kept =
        if (shape.namespace == Identifier.DEFAULT_NAMESPACE && shape.path.startsWith(VANILLAS_MOON_FOLDER)) {
            VANILLAS_MOON
        } else {
            WHOLE_SPRITE
        }

    /** A window into a sprite, as a fraction of it in both axes. */
    private data class Kept(val from: Float, val to: Float)

    private val WHOLE_SPRITE = Kept(0.0f, 1.0f)

    /**
     * The middle 8 by 8 of vanilla's 32 by 32 moon sprites — measured, not guessed. Every phase's content
     * lies within `x 12..19, y 12..19` and none outside it; `CelestialTextureCheck` holds that.
     */
    private val VANILLAS_MOON = Kept(12.0f / 32.0f, 20.0f / 32.0f)

    private const val VANILLAS_MOON_FOLDER = "moon/"

    /**
     * The unit slab every deck is drawn from, built once and shared. Corners are `±1`; [drawCloudDeck]
     * scales them to the deck's width and thickness, and the only per-vertex datum is a face brightness
     * in the colour's red channel.
     *
     * No interior subdivision, because the roil is read per fragment rather than per vertex.
     */
    private fun slab(): GpuBuffer = slabBuffer ?: buildSlab().also { slabBuffer = it }

    private fun buildSlab(): GpuBuffer {
        val format = DefaultVertexFormat.POSITION_COLOR
        return ByteBufferBuilder.exactlySized(SLAB_QUADS * QUAD_VERTICES * format.vertexSize).use { bytes ->
            val builder = BufferBuilder(bytes, VertexFormat.Mode.QUADS, format)

            fun corner(x: Float, y: Float, z: Float, brightness: Float) {
                builder.addVertex(x, y, z).setColor(brightness, brightness, brightness, 1.0f)
            }

            fun quad(brightness: Float, corners: List<Triple<Float, Float, Float>>) {
                for ((x, y, z) in corners) corner(x, y, z, brightness)
            }

            // Winding is irrelevant; the pipeline draws both faces.
            quad(TOP_BRIGHTNESS, listOf(t(-1, 1, -1), t(-1, 1, 1), t(1, 1, 1), t(1, 1, -1)))
            quad(BOTTOM_BRIGHTNESS, listOf(t(-1, -1, -1), t(1, -1, -1), t(1, -1, 1), t(-1, -1, 1)))
            quad(SIDE_BRIGHTNESS, listOf(t(1, 1, -1), t(1, 1, 1), t(1, -1, 1), t(1, -1, -1)))
            quad(SIDE_BRIGHTNESS, listOf(t(-1, 1, 1), t(-1, 1, -1), t(-1, -1, -1), t(-1, -1, 1)))
            quad(SIDE_BRIGHTNESS, listOf(t(1, 1, 1), t(-1, 1, 1), t(-1, -1, 1), t(1, -1, 1)))
            quad(SIDE_BRIGHTNESS, listOf(t(-1, 1, -1), t(1, 1, -1), t(1, -1, -1), t(-1, -1, -1)))

            builder.buildOrThrow().use { mesh ->
                RenderSystem.getDevice().createBuffer({ "Ephemeris cloud slab" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
            }
        }
    }

    /** Just to keep the corner tables above readable as coordinates rather than as float noise. */
    private fun t(x: Int, y: Int, z: Int) = Triple(x.toFloat(), y.toFloat(), z.toFloat())

    /** This level's stars, built once and kept. */
    private fun starfieldOf(seed: Long, count: Int): Starfield = starfields.getOrPut(seed to count) {
        buildStarfield(seed, count)
    }

    /**
     * Places [count] stars on the sky sphere, each a small quad lying flat against it, carrying its own
     * tint and twinkle.
     *
     * Placed rather than rejected, unlike vanilla's — it samples a cube and drops what falls outside, so
     * "1500" is an attempt rather than a count. A writer asking for a number of stars should get it.
     */
    private fun buildStarfield(seed: Long, count: Int): Starfield {
        val random = Random(seed)
        val starSphere = Sphere(STAR_DISTANCE)
        val format = DefaultVertexFormat.POSITION_TEX_COLOR
        return ByteBufferBuilder.exactlySized(count * QUAD_VERTICES * format.vertexSize).use { bytes ->
            val builder = BufferBuilder(bytes, VertexFormat.Mode.QUADS, format)
            repeat(count) {
                val center = starSphere.randomSurfacePoint(random)
                val halfSize = MIN_STAR_SIZE + random.nextDouble() * STAR_SIZE_VARIATION
                val spin = random.nextDouble() * FULL_CIRCLE_RADIANS
                val tint = WARM_STAR.lerp(COOL_STAR, random.nextFloat())
                val phase = (random.nextDouble() * FULL_CIRCLE_RADIANS).toFloat()
                val rate = SLOWEST_TWINKLE_RATE * (1 + random.nextInt(TWINKLE_RATES))
                for (corner in starSphere.tangentQuad(center, halfSize, spin)) {
                    builder.addVertex(corner.x.toFloat(), corner.y.toFloat(), corner.z.toFloat())
                        .setUv(phase, rate)
                        .setColor(tint.red, tint.green, tint.blue, tint.alpha)
                }
            }
            builder.buildOrThrow().use { mesh ->
                val buffer = RenderSystem.getDevice()
                    .createBuffer({ "Ephemeris starfield" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())
                Starfield(buffer, mesh.drawState().indexCount())
            }
        }
    }
}
