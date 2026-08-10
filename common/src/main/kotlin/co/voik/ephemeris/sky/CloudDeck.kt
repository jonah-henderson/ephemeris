package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * One overcast cloud layer: a flat slab at [height] that roils between [low] and [high].
 *
 * **A deck is data, like everything else in a [SkySpec]** — it travels to the client on the payload and is
 * rebuilt on every open, so nothing about how it is drawn belongs here.
 *
 * The roil is a closed-form function of world position and time, which is what lets the renderer evaluate
 * it per *fragment* rather than per vertex. [driftSpeed] is how fast it moves and [noiseOffsetX] /
 * [noiseOffsetZ] shift it into a different region of the field — two decks over the same ground want
 * different offsets, or they mirror each other and read as one thick layer.
 */
data class CloudDeck(
    /** World Y of the slab's middle. */
    val height: Double,
    /** The tone the thin parts settle to. */
    val low: Rgba,
    /** The tone the dense parts reach. */
    val high: Rgba,
    val driftSpeed: Float,
    /** Zero is as good a region of the field as any, so only a *second* deck needs to say anything. */
    val noiseOffsetX: Double = 0.0,
    val noiseOffsetZ: Double = 0.0,
    /** Half the slab's vertical extent. Vanilla's own clouds are 4 blocks thick, so 2 matches them. */
    val halfThickness: Float = VANILLA_HALF_THICKNESS,
    /** How hard the roil is pushed toward its extremes; 1 leaves it as sampled. */
    val contrast: Float = DEFAULT_CONTRAST,
) {
    companion object {
        const val VANILLA_HALF_THICKNESS = 2.0f
        const val DEFAULT_CONTRAST = 1.6f

        val CODEC: Codec<CloudDeck> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("height").forGetter(CloudDeck::height),
                Rgba.CODEC.fieldOf("low").forGetter(CloudDeck::low),
                Rgba.CODEC.fieldOf("high").forGetter(CloudDeck::high),
                Codec.FLOAT.fieldOf("drift").forGetter(CloudDeck::driftSpeed),
                Codec.DOUBLE.optionalFieldOf("noise_offset_x", 0.0).forGetter(CloudDeck::noiseOffsetX),
                Codec.DOUBLE.optionalFieldOf("noise_offset_z", 0.0).forGetter(CloudDeck::noiseOffsetZ),
                Codec.FLOAT.optionalFieldOf("half_thickness", VANILLA_HALF_THICKNESS)
                    .forGetter(CloudDeck::halfThickness),
                Codec.FLOAT.optionalFieldOf("contrast", DEFAULT_CONTRAST).forGetter(CloudDeck::contrast),
            ).apply(instance, ::CloudDeck)
        }
    }
}

/**
 * A band of height across which the stars fade in, for a sky that keeps them hidden until you climb.
 *
 * Build one **from the deck it clears** rather than writing the heights down beside it. The coupling is the
 * point: retuning a deck then carries its stars with it instead of leaving them fading at the old height.
 */
data class StarReveal(val hiddenBelow: Double, val fullyShownAbove: Double) {

    /** How much of the starfield is showing at [eyeY], in `0.0..1.0`. Hermite, so the fade has no corners. */
    fun visibilityAt(eyeY: Double): Float {
        val progress = ((eyeY - hiddenBelow) / (fullyShownAbove - hiddenBelow)).coerceIn(0.0, 1.0)
        return (progress * progress * (3.0 - 2.0 * progress)).toFloat()
    }

    companion object {
        val CODEC: Codec<StarReveal> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("hidden_below").forGetter(StarReveal::hiddenBelow),
                Codec.DOUBLE.fieldOf("fully_shown_above").forGetter(StarReveal::fullyShownAbove),
            ).apply(instance, ::StarReveal)
        }
    }
}
