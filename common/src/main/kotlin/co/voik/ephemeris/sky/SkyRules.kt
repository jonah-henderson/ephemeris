package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * What a level's sky is allowed to decide, and how — **per level, never globally**.
 *
 * A sky with three suns raises a question vanilla never had to answer: when is it *day*? The honest answer
 * depends on what the level is for, so it is asked of each level rather than settled once. Rules ride with
 * the appearance in [LevelLook] because both sides need them and one object cannot disagree with itself:
 * the server reads [daylight], a client reads [glow], and neither can be told a different story.
 */
data class SkyRules(
    val daylight: Daylight = Daylight.EVERY_SUN,
    /**
     * Which body [Daylight.PRIMARY_SUN] follows, as an index into [SkySpec.bodies].
     *
     * Out of range means the first body, so a spec that loses a sun degrades to something sensible rather
     * than throwing in the middle of a tick.
     */
    val primaryBody: Int = 0,
    val glow: HorizonGlow = HorizonGlow.BLENDED,
) {
    companion object {
        val DEFAULT = SkyRules()

        val CODEC: Codec<SkyRules> = RecordCodecBuilder.create { instance ->
            instance.group(
                Daylight.CODEC.optionalFieldOf("daylight", Daylight.EVERY_SUN).forGetter(SkyRules::daylight),
                Codec.INT.optionalFieldOf("primary_body", 0).forGetter(SkyRules::primaryBody),
                HorizonGlow.CODEC.optionalFieldOf("glow", HorizonGlow.BLENDED).forGetter(SkyRules::glow),
            ).apply(instance, ::SkyRules)
        }
    }
}

/**
 * How a level decides whether it is day.
 *
 * Whatever this settles, **vanilla derives the rest from it**: the light level, hostile spawning, phantoms,
 * sleeping, daylight sensors and villager schedules all read one clock, so moving that clock moves all of
 * them together and none of them can drift apart.
 */
enum class Daylight(private val key: String) {
    /**
     * Day while **any** sun is above the horizon; night only once they have all gone. The default, and the
     * reading that matches what a player sees out of a window.
     *
     * A level whose sun never sets is therefore in permanent day in every sense vanilla understands — no
     * hostile spawning outdoors, and no sleeping. That is a consequence of the rule rather than a bug in it.
     */
    EVERY_SUN("every_sun"),

    /**
     * Day while **one named sun** is up, whatever the others are doing — [SkyRules.primaryBody].
     *
     * For a level that wants companion suns to be scenery: a second sun can wander the night sky without
     * suppressing the mobs, and a player can still sleep under it.
     */
    PRIMARY_SUN("primary_sun"),

    /**
     * Don't decide. The level keeps vanilla's own day and night **even where the sky visibly disagrees**.
     *
     * The escape hatch, and a real choice rather than a broken one: a level that only wants a strange sky
     * and vanilla's rhythms underneath should say so here, instead of having its gameplay quietly reshaped
     * by a decorative second sun.
     */
    VANILLA_CLOCK("vanilla_clock"),
    ;

    companion object {
        val CODEC: Codec<Daylight> = Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: EVERY_SUN },
            { it.key },
        )
    }
}

/** How the light at the horizon is painted when more than one body is near it. */
enum class HorizonGlow(private val key: String) {
    /**
     * Each sun paints its own glow at its own bearing, in its own colour, and the sum is normalised.
     *
     * The default, because two sunsets should read as two events rather than as one brighter one — a red
     * giant setting in the west and a small blue sun rising in the east stay legible as themselves.
     */
    BLENDED("blended"),

    /**
     * The same glows, simply summed. Physically the honest one — two suns near the horizon really is twice
     * the light — and it will clip toward white when several align, which is sometimes the point.
     */
    ADDITIVE("additive"),

    /** Only the sun nearest the horizon paints anything. Cheapest, always legible, and hides the others. */
    NEAREST("nearest"),

    /** No glow at all, for a sky with nothing to scatter it. */
    NONE("none"),
    ;

    companion object {
        val CODEC: Codec<HorizonGlow> = Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: BLENDED },
            { it.key },
        )
    }
}
