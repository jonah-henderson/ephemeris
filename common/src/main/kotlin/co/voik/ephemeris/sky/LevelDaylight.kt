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
     * sky, and [Daylight.VANILLA_CLOCK] — which is a consumer saying explicitly that the visuals may disagree
     * with the rules. **A sky with no sun is night**, as its look is held at midnight: left to vanilla's clock,
     * the land was lit by day under a midnight sky. A glaring moon still lifts it.
     */
    fun skyDarkenFor(level: Level): Int? {
        val look = LevelLooks.anywhere(level) ?: return null
        if (look.sky.isOrdinary) return null
        if (look.rules.daylight == Daylight.VANILLA_CLOCK) return null

        val reading = look.readAt(level.defaultClockTime)
        val deciding = decidingSun(reading, look.rules) ?: return moonlit(NIGHT_DARKENING, reading)
        return moonlit(darkeningUnder(look.sky, deciding, reading.dayTime), reading)
    }

    /**
     * [darkening] lightened by the moons up in [reading]: a moon that glares lifts the night by up to
     * [MOST_MOONLIGHT_LIFTS] steps when it is full and high, enough at the top to keep monsters from the open, and a
     * moon never darkens what the sun has lit.
     */
    fun moonlit(darkening: Int, reading: SkyReading): Int {
        val sun = reading.suns.firstOrNull()?.body?.path
        val brightest = reading.moons.maxOfOrNull { moonlightOf(it, sun, reading.dayTime) } ?: return darkening
        return minOf(darkening, NIGHT_DARKENING - Math.round(brightest * MOST_MOONLIGHT_LIFTS))
    }

    /**
     * How much the brightest moon in [look] lights the night at [dayTime], `0..1`: what lifts the light you see by night,
     * as [moonlit] lifts the darkening the world's rules read.
     */
    fun moonlightOf(look: LevelLook, dayTime: Long): Float {
        val reading = look.readAt(dayTime)
        val sun = reading.suns.firstOrNull()?.body?.path
        return (reading.moons.maxOfOrNull { moonlightOf(it, sun, reading.dayTime) } ?: 0.0f).coerceIn(0.0f, 1.0f)
    }

    /** How much [moon] lights the night, `0..1`: by its glare, how full it shows and how high it stands. */
    private fun moonlightOf(moon: BodyReading, sun: CelestialPath?, dayTime: Long): Float {
        val glare = moon.body.glare
        val phase = moon.body.phase
        if (glare <= 0.0f || phase == null) return 0.0f
        val fullness = phase.fullnessAt(phase.stepShownAt(dayTime, moon.body.path, sun))
        val risen = Mth.clamp(Mth.inverseLerp(moon.altitudeDegrees, 0.0f, MOONLIGHT_WHOLE_ABOVE), 0.0f, 1.0f)
        return glare * fullness * risen * risen * (3.0f - 2.0f * risen)
    }

    /**
     * How dark it is under [deciding]: by its height, and **deeper still while something covers it or it is dim**
     * — an eclipse and a dim sun are what darken a sun standing high, and they darken it for everything that reads
     * the light, the spawning of monsters and the stars included.
     */
    fun darkeningUnder(sky: SkySpec, deciding: BodyReading, dayTime: Long): Int {
        val coverage = Eclipse.of(sky, deciding.index, dayTime)?.coverage ?: 0.0f
        val dimmed = darkeningDimmedBy(deciding.body.dimming)
        return maxOf(darkeningFor(deciding.altitudeDegrees, deciding.body.glare), darkeningCoveredBy(coverage), dimmed)
    }

    /** How dark a sun of [dimming] holds the day at its brightest, on the same `0..11` scale. */
    fun darkeningDimmedBy(dimming: Float): Int = Math.round(dimming.coerceIn(0.0f, 1.0f) * NIGHT_DARKENING)

    /**
     * How dim the sun [look]'s level follows is at [dayTime], as far as the air shows it: its [CelestialBody.dimming]
     * while it is up, fading as it sets as an eclipse's look does ([Eclipse.lookAt]), since night is as dark.
     */
    fun dimmingOf(look: LevelLook, dayTime: Long): Float {
        val deciding = decidingSunOf(look, dayTime) ?: return 0.0f
        return deciding.body.dimming * Eclipse.lookAt(deciding.altitudeDegrees)
    }

    /**
     * How much more of the day's light a glaring sun leaves at [dayTime] than an ordinary one at its height, `0..1`:
     * nothing at noon or midnight, most of it through the twilight it lengthens.
     */
    fun lingeringOf(look: LevelLook, dayTime: Long): Float {
        val deciding = decidingSunOf(look, dayTime) ?: return 0.0f
        val glare = deciding.body.glare
        if (glare <= 0.0f) return 0.0f
        return brightnessFor(deciding.altitudeDegrees, glare) - brightnessFor(deciding.altitudeDegrees, 0.0f)
    }

    /** The sun [look]'s level follows at [dayTime], or null where it follows none of its own. */
    fun decidingSunOf(look: LevelLook, dayTime: Long): BodyReading? {
        if (look.sky.isOrdinary || look.rules.daylight == Daylight.VANILLA_CLOCK) return null
        return decidingSun(look.readAt(dayTime), look.rules)
    }

    /**
     * How dark a sun [coverage] covered leaves the day, on the same `0..11` scale ([Eclipse.shadowOf]): about two
     * steps at an annular eclipse's four-fifths, six at nine-tenths, and night's own at totality.
     */
    fun darkeningCoveredBy(coverage: Float): Int = Math.round(Eclipse.shadowOf(coverage) * NIGHT_DARKENING)

    /**
     * The eclipse of the sun [look]'s level follows at [dayTime], or null where nothing covers it — or where the
     * level keeps vanilla's clock, and so follows no sun of its own.
     */
    fun eclipseOf(look: LevelLook, dayTime: Long): Eclipse? {
        if (look.sky.isOrdinary || look.rules.daylight == Daylight.VANILLA_CLOCK) return null
        val deciding = decidingSun(look.readAt(dayTime), look.rules) ?: return null
        return Eclipse.of(look.sky, deciding.index, dayTime)
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
    fun darkeningFor(altitudeDegrees: Float, glare: Float = 0.0f): Int =
        Math.round(Mth.lerp(brightnessFor(altitudeDegrees, glare), NIGHT_DARKENING.toFloat(), DAY_DARKENING.toFloat()))

    /**
     * How lit the day is with a sun at [altitudeDegrees], `0..1`, eased. A sun of [glare] keeps its light further
     * under the horizon, so its twilight lasts longer at both ends of the day.
     */
    private fun brightnessFor(altitudeDegrees: Float, glare: Float): Float {
        val darkBelow = FULLY_DARK_BELOW - glare.coerceIn(0.0f, 1.0f) * GLARE_LINGERS_DEGREES
        val brightness = Mth.clamp(Mth.inverseLerp(altitudeDegrees, darkBelow, FULLY_LIT_ABOVE), 0.0f, 1.0f)
        return brightness * brightness * (3.0f - 2.0f * brightness)
    }

    /**
     * **How far the stars come through**, on the same ramp and from the same sun the light follows — or
     * null where vanilla's own answer is the better one.
     *
     * Vanilla drives `STAR_BRIGHTNESS` and `SKY_LIGHT_FACTOR` as two independent keyframe tracks that
     * happen to be in antiphase, so the stars are told what hour it is rather than how lit the sky is.
     * That is exact for one sun on vanilla's path and wrong for every other sky: an Age with no sun at all
     * still read "noon, stars off" and stood pitch dark under an empty sky (Jonah, 2026-08-25, walked).
     *
     * Following the light instead makes the three cases fall out of one rule rather than being written
     * down: **no sun means the sky is never lit**, so the stars are always out; **several suns** keep one
     * above the horizon most of the day, so they rarely are; and one sun on vanilla's path reproduces
     * vanilla, that being what [darkeningFor] is a ramp of.
     *
     * **Only the fade.** Where the stars *are* is [co.voik.ephemeris.client.SkyPainter]'s business and
     * turns on the level's clock, so they still wheel over a sunless Age at the rate its day runs at.
     */
    fun starlitnessFor(level: Level): Float? {
        val look = LevelLooks.anywhere(level) ?: return null
        if (look.sky.isOrdinary) return null
        if (look.rules.daylight == Daylight.VANILLA_CLOCK) return null

        return starlitnessOf(look.readAt(level.defaultClockTime), look.rules, look.sky)
    }

    /**
     * The same, of a reading rather than a level — pure, so the three cases can be checked without a game. Given
     * the [sky] the reading was taken of, an eclipse brings the stars out too.
     *
     * **A sky with no sun in it is never lit**, whatever hour the clock says, and that is the one case
     * that cannot be read off an altitude because there is none to read. [skyDarkenFor] holds the world at
     * night for the same absence.
     */
    fun starlitnessOf(reading: SkyReading, rules: SkyRules, sky: SkySpec? = null): Float {
        val deciding = decidingSun(reading, rules) ?: return FULLY_STARLIT
        val darkening = sky?.let { darkeningUnder(it, deciding, reading.dayTime) } ?: darkeningFor(deciding.altitudeDegrees)
        return moonlit(darkening, reading) / NIGHT_DARKENING.toFloat()
    }

    /** Stars at their fullest — what a sky with nothing lighting it shows at every hour. */
    private const val FULLY_STARLIT = 1.0f

    /** Where the ramp begins and ends, in degrees of altitude. */
    private const val FULLY_LIT_ABOVE = 5.0f
    private const val FULLY_DARK_BELOW = -11.0f

    /**
     * How many steps of the night's darkness a full moon at full glare lifts. A glare of 0.8 lifts four, leaving the
     * open at a sky light of eight, above the seven vanilla's overworld lets monsters spawn at.
     */
    private const val MOST_MOONLIGHT_LIFTS = 5.0f

    /** How high a moon stands before its light is whole, in degrees; it gives none below the horizon. */
    private const val MOONLIGHT_WHOLE_ABOVE = 15.0f

    /** How much further under the horizon a sun at full glare is before its light is gone, in degrees. */
    private const val GLARE_LINGERS_DEGREES = 14.0f

    /**
     * Vanilla's own ends of the scale: `skyDarken` is `15 - skyLightLevel`, and the overworld's light runs
     * between 15 by day and 4 by night (`Timelines.DAY_SKY_LIGHT_LEVEL` / `NIGHT_SKY_LIGHT_LEVEL`).
     */
    private const val DAY_DARKENING = 0
    private const val NIGHT_DARKENING = 11
}
