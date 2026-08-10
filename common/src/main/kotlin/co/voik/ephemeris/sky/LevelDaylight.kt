package co.voik.ephemeris.sky

import net.minecraft.util.Mth
import net.minecraft.world.level.Level

/**
 * Making the level under a sky agree with it — **the whole of "auto" mode, and it is one number**.
 *
 * 26.1 funnels day and night through `Level.skyDarken`, which `updateSkyBrightness` recomputes each tick as
 * `15 - SKY_LIGHT_LEVEL`. Everything downstream reads that one field: `isBrightOutside` and `isDarkOutside`
 * and therefore hostile spawning, phantoms, sleeping, daylight sensors, villager schedules and the block
 * lighting itself. So a sky that wants the world to follow it does not need to reach into any of them — it
 * needs to answer that number, and vanilla derives the rest exactly as it always did.
 *
 * **Nothing is touched unless the sky is genuinely unusual.** A level whose spec is
 * [SkySpec.isOrdinary] keeps vanilla's own curve untouched, keyframe for keyframe: our ramp is a good
 * approximation of it and an approximation is not what an ordinary sky should get. Only a sky vanilla could
 * not have drawn gets an answer of ours.
 */
object LevelDaylight {

    /**
     * What [level]'s sky darkening should be, or **null** to leave vanilla's alone.
     *
     * Null covers every case where we have nothing better to say: a level with no appearance, an ordinary
     * sky, a sky with no suns at all, and [Daylight.VANILLA_CLOCK] — which is a consumer saying explicitly
     * that the visuals may disagree with the rules.
     */
    fun skyDarkenFor(level: Level): Int? {
        val look = LevelLooks.anywhere(level.dimension()) ?: return null
        if (look.sky.isOrdinary) return null
        if (look.rules.daylight == Daylight.VANILLA_CLOCK) return null

        val reading = look.readAt(level.defaultClockTime)
        val deciding = decidingSun(reading, look.rules) ?: return null
        return darkeningFor(deciding.altitudeDegrees)
    }

    /**
     * Which sun the level follows.
     *
     * Under [Daylight.EVERY_SUN] that is whichever stands highest, so the brightest moment any sun offers is
     * the one the world gets — which is what makes "day while any sun is up" true of the light and not only
     * of the flag.
     */
    private fun decidingSun(reading: SkyReading, rules: SkyRules): BodyReading? = when (rules.daylight) {
        Daylight.EVERY_SUN -> reading.suns.maxByOrNull { it.altitudeDegrees }
        Daylight.PRIMARY_SUN -> reading.primaryUnder(rules)?.takeIf { it.isSun } ?: reading.suns.firstOrNull()
        Daylight.VANILLA_CLOCK -> null
    }

    /**
     * How dark the sky is with a sun at [altitudeDegrees], on vanilla's own `0..11` scale.
     *
     * The band either side of the horizon is where the ramp happens, and it is deliberately a little wider
     * below than above: vanilla's own light lingers after sunset and is gone before the sun is properly up,
     * which is what makes dusk longer than dawn feels. Smoothstepped rather than linear so neither end has a
     * corner in it, because a corner in the light is visible as a flicker when it crosses a block boundary.
     *
     * **Approximate, and only ever applied to a sky vanilla could not have drawn.** Nothing here tries to
     * reproduce `Timelines.OVERWORLD_DAY` keyframe for keyframe — a sky with three suns has no keyframes to
     * be faithful to.
     */
    fun darkeningFor(altitudeDegrees: Float): Int {
        val brightness = Mth.clamp(
            Mth.inverseLerp(altitudeDegrees, FULLY_DARK_BELOW, FULLY_LIT_ABOVE),
            0.0f,
            1.0f,
        )
        val eased = brightness * brightness * (3.0f - 2.0f * brightness)
        return Math.round(Mth.lerp(eased, NIGHT_DARKENING.toFloat(), DAY_DARKENING.toFloat()))
    }

    /** Where the ramp begins and ends, in degrees of altitude. */
    private const val FULLY_LIT_ABOVE = 5.0f
    private const val FULLY_DARK_BELOW = -11.0f

    /**
     * Vanilla's own ends of the scale: `skyDarken` is `15 - skyLightLevel`, and the overworld's light runs
     * between 15 by day and 4 by night (`Timelines.DAY_SKY_LIGHT_LEVEL` / `NIGHT_SKY_LIGHT_LEVEL`).
     */
    private const val DAY_DARKENING = 0
    private const val NIGHT_DARKENING = 11
}
