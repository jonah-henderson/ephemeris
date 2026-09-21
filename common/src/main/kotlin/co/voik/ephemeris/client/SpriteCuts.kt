package co.voik.ephemeris.client

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.resources.Identifier

/**
 * How a body's sprite is drawn when it **covers** the sky rather than adding to it.
 *
 * Covering needs a sprite that says which of its pixels are sky, and not every sprite does. Vanilla's carry
 * no alpha at all, so Ephemeris crops theirs instead — see `Blaze3dSkyCanvas.keptOf`, which cuts the moon's
 * painted-on night sky away by the measured window rather than by testing colours.
 * A consumer's own texture is assumed to carry its own transparency and is drawn as it is.
 *
 * **This is the escape hatch for anything else.** A texture that needs a different rule — a chroma key, a
 * mask in another channel, a body that should glow at its rim — registers a pipeline here against the sprite
 * it applies to, and that pipeline is used in place of either default. Nothing else about the body changes:
 * it is still placed by its path, laid level by it and tinted by its appearance.
 *
 * Adding is untouched by any of this. Dark contributes nothing to a sum, which is the whole reason vanilla's
 * sprites can have no alpha and still look right.
 */
object SpriteCuts {

    private val registered = mutableMapOf<Identifier, RenderPipeline>()

    /**
     * Draw [shape] with [pipeline] whenever it covers.
     *
     * The pipeline must take `POSITION_TEX` quads, a `Sampler0`, and the `DynamicTransforms` and `Projection`
     * uniforms — the same contract vanilla's `core/position_tex` meets, which is the easiest thing to start
     * from.
     */
    fun register(shape: Identifier, pipeline: RenderPipeline) {
        registered[shape] = pipeline
    }

    /** What a consumer registered for [shape], or null to let Ephemeris decide. */
    fun of(shape: Identifier): RenderPipeline? = registered[shape]
}
