package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * How a level is coloured — the half of its appearance a server cannot decide alone.
 *
 * **The air, and what grows under it.** Most of this is the air the eye sees through; [grass], [foliage],
 * [dryFoliage] and [water] are the tints vanilla resolves off the *biome*, which is why they have to be here rather
 * than on a biome: a runtime level borrows the registry's biomes, and repainting one of those would repaint
 * it in the overworld too. Answering per level instead leaves the registry alone entirely.
 *
 * Every field is **null where nothing said**, and a null is not a colour: it means "whatever the layer below
 * produced", which is how a level can repaint its sky and leave its fog exactly as vanilla lit it.
 *
 * [haze] and [ceiling] are fractions of their own axis rather than distances in blocks, because a caller
 * says how thick the air is and only the client knows how far it can see.
 */
data class Look(
    val sky: Rgba? = null,
    val fog: Rgba? = null,
    val cloud: Rgba? = null,
    val tint: Rgba? = null,
    /** The particle that hangs in the air, by its registry id. */
    val motes: String? = null,
    val haze: Float? = null,
    val ceiling: Float? = null,
    val murk: Float? = null,
    /**
     * What grass is tinted, over whatever the biome would have said.
     *
     * **Laid under the biome's own modifier, not instead of it** — a swamp's mottling and a dark forest's
     * darkening are shape rather than colour, and an Age that repaints the grass should still have a swamp
     * that reads as one. See `GroundTints`.
     */
    val grass: Rgba? = null,
    /** What leaves are tinted, the same way. */
    val foliage: Rgba? = null,
    /** And leaf litter and dead brush, which vanilla resolves separately from live leaves. */
    val dryFoliage: Rgba? = null,
    /** What water is tinted, the same way: its surface, its flow and a cauldron's. */
    val water: Rgba? = null,
    /** The colour of the fog seen from under the water. */
    val waterFog: Rgba? = null,
    /**
     * How brightly the stars burn, overriding the day's own curve — `1.0` being midnight.
     *
     * **How a sky with no sun stops having a noon.** Vanilla drives star brightness off the timeline, so a
     * level with nothing overhead still spends half its day under a bright empty sky and its stars still fade
     * out at noon. Pinning this is the whole of "locked at midnight" for such a place: how much light the
     * ground actually gets is a level attribute, and this is only what the eye sees overhead.
     */
    val starBrightness: Float? = null,
) {
    val saysNothing: Boolean
        get() = sky == null && fog == null && cloud == null && tint == null &&
            motes == null && haze == null && ceiling == null && murk == null && starBrightness == null &&
            grass == null && foliage == null && dryFoliage == null && water == null && waterFog == null

    /**
     * This look over [under] — every colour of ours that was named, and [under]'s where it was not.
     *
     * Which way round matters. The lower look is what a level looks like *before* anything specific was
     * said, so repainting one colour of an elaborate sky leaves the rest of it standing.
     */
    fun over(under: Look): Look = Look(
        sky = sky ?: under.sky,
        fog = fog ?: under.fog,
        cloud = cloud ?: under.cloud,
        tint = tint ?: under.tint,
        motes = motes ?: under.motes,
        haze = haze ?: under.haze,
        ceiling = ceiling ?: under.ceiling,
        murk = murk ?: under.murk,
        starBrightness = starBrightness ?: under.starBrightness,
        grass = grass ?: under.grass,
        foliage = foliage ?: under.foliage,
        dryFoliage = dryFoliage ?: under.dryFoliage,
        water = water ?: under.water,
        waterFog = waterFog ?: under.waterFog,
    )

    companion object {
        val NOTHING = Look()

        val CODEC: Codec<Look> = RecordCodecBuilder.create { instance ->
            instance.group(
                Rgba.CODEC.optionalFieldOf("sky").forGetter { java.util.Optional.ofNullable(it.sky) },
                Rgba.CODEC.optionalFieldOf("fog").forGetter { java.util.Optional.ofNullable(it.fog) },
                Rgba.CODEC.optionalFieldOf("cloud").forGetter { java.util.Optional.ofNullable(it.cloud) },
                Rgba.CODEC.optionalFieldOf("tint").forGetter { java.util.Optional.ofNullable(it.tint) },
                Codec.STRING.optionalFieldOf("motes").forGetter { java.util.Optional.ofNullable(it.motes) },
                Codec.FLOAT.optionalFieldOf("haze").forGetter { java.util.Optional.ofNullable(it.haze) },
                Codec.FLOAT.optionalFieldOf("ceiling").forGetter { java.util.Optional.ofNullable(it.ceiling) },
                Codec.FLOAT.optionalFieldOf("murk").forGetter { java.util.Optional.ofNullable(it.murk) },
                Codec.FLOAT.optionalFieldOf("star_brightness")
                    .forGetter { java.util.Optional.ofNullable(it.starBrightness) },
                Rgba.CODEC.optionalFieldOf("grass").forGetter { java.util.Optional.ofNullable(it.grass) },
                Rgba.CODEC.optionalFieldOf("foliage").forGetter { java.util.Optional.ofNullable(it.foliage) },
                Rgba.CODEC.optionalFieldOf("dry_foliage")
                    .forGetter { java.util.Optional.ofNullable(it.dryFoliage) },
                Rgba.CODEC.optionalFieldOf("water").forGetter { java.util.Optional.ofNullable(it.water) },
                Rgba.CODEC.optionalFieldOf("water_fog").forGetter { java.util.Optional.ofNullable(it.waterFog) },
            ).apply(instance) { sky, fog, cloud, tint, motes, haze, ceiling, murk, starBrightness,
                                grass, foliage, dryFoliage, water, waterFog ->
                Look(
                    sky = sky.orElse(null),
                    fog = fog.orElse(null),
                    cloud = cloud.orElse(null),
                    tint = tint.orElse(null),
                    motes = motes.orElse(null),
                    haze = haze.orElse(null),
                    ceiling = ceiling.orElse(null),
                    murk = murk.orElse(null),
                    starBrightness = starBrightness.orElse(null),
                    grass = grass.orElse(null),
                    foliage = foliage.orElse(null),
                    dryFoliage = dryFoliage.orElse(null),
                    water = water.orElse(null),
                    waterFog = waterFog.orElse(null),
                )
            }
        }
    }
}
