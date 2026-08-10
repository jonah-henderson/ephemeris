package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier

/**
 * One overcast cloud layer at [height], shaped by a [texture] and shaded by a roil between [low] and [high].
 *
 * **A deck is data, like everything else in a [SkySpec]** — it travels to the client on the payload and is
 * rebuilt on every open, so nothing about how it is drawn belongs here.
 *
 * **Two entirely separate things decide what a deck looks like, and conflating them wastes an afternoon.**
 *
 * - **[texture] decides where there is cloud and where there is sky.** It is read the way vanilla reads its
 *   own `clouds.png`: as a grid of [CELL_BLOCKS]-block cells, cloud wherever a pixel is opaque. So the
 *   silhouette is vanilla's, and a consumer wanting thinner or thicker cover supplies a different picture
 *   rather than asking for a number — which is both simpler and more expressive than any set of presets.
 *   **Null is a deck with no holes at all**: see [solid].
 * - **The roil decides the *tone* of the cloud that is there**, drifting between [low] and [high]. It is
 *   shading and never coverage; [contrast] pushes it toward its extremes and cuts nothing away.
 *
 * The roil is a closed-form function of world position and time, which is what lets the renderer evaluate it
 * per *fragment* rather than per vertex. [driftSpeed] is how fast it moves and [noiseOffsetX] /
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
    /** How hard the roil's **shading** is pushed toward its extremes; 1 leaves it as sampled. */
    val contrast: Float = DEFAULT_CONTRAST,
    /**
     * The picture the deck is cut from, or **null** for a deck with no holes.
     *
     * Any texture will do, and Ephemeris ships none: the default is vanilla's own, so a deck that says
     * nothing looks like the overworld's clouds at a height of its choosing.
     */
    val texture: Identifier? = VANILLA_CLOUDS,
) {
    companion object {
        const val VANILLA_HALF_THICKNESS = 2.0f
        const val DEFAULT_CONTRAST = 1.6f

        /** Vanilla's own cloud picture. Ephemeris ships no textures; it borrows this one. */
        val VANILLA_CLOUDS: Identifier = Identifier.withDefaultNamespace("textures/environment/clouds.png")

        /**
         * How wide one pixel of a cloud texture is, in blocks. Vanilla's `CELL_SIZE_IN_BLOCKS`, so a deck
         * cut from vanilla's own picture is the size the overworld's clouds are.
         */
        const val CELL_BLOCKS = 12.0f

        /**
         * A deck with no holes — the exotic one, and the reason the roil exists at all.
         *
         * Under an unbroken ceiling the roil is the only thing distinguishing one part of the sky from
         * another, which is why a solid deck usually wants a wider [contrast] than a cut one.
         */
        fun solid(
            height: Double,
            low: Rgba,
            high: Rgba,
            driftSpeed: Float,
            halfThickness: Float = VANILLA_HALF_THICKNESS,
            contrast: Float = DEFAULT_CONTRAST,
        ): CloudDeck = CloudDeck(
            height = height,
            low = low,
            high = high,
            driftSpeed = driftSpeed,
            halfThickness = halfThickness,
            contrast = contrast,
            texture = null,
        )

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
                // Absent means vanilla's picture; an explicit null cannot be written, so a solid deck is
                // said with the `solid` factory rather than in JSON. Nothing authors one yet.
                Identifier.CODEC.optionalFieldOf("texture", VANILLA_CLOUDS).forGetter {
                    it.texture ?: VANILLA_CLOUDS
                },
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
