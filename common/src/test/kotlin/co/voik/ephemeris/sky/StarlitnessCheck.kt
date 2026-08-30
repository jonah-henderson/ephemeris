package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec

/**
 * **When the stars come out**, which is a question about how lit the sky is and not about what hour it is.
 *
 * Vanilla writes `STAR_BRIGHTNESS` and `SKY_LIGHT_FACTOR` as two independent keyframe tracks and keeps them
 * in antiphase by hand. That is exact for one sun on vanilla's own path and wrong for every sky vanilla
 * could not have drawn — a level with no sun at all read "noon, stars off" and stood pitch dark under an
 * empty sky (Jonah, 2026-08-25, walked).
 *
 * [LevelDaylight.starlitnessOf] answers off the same sun and the same ramp the level's own light already
 * follows, so the two cannot disagree. What that buys is that the awkward cases stop being special: they
 * fall out of one rule.
 */
class StarlitnessCheck : FunSpec({

    val dawn = 23000L
    val noon = 6000L
    val dusk = 13000L
    val midnight = 18000L

    fun sun(orbit: Orbit) = CelestialBody(orbit, Appearance.Sprite(Rgba.WHITE, 30.0f, Appearance.SUN_SHAPES))
    fun moon(orbit: Orbit) =
        CelestialBody(orbit, Appearance.Sprite(Rgba.WHITE, 20.0f, Appearance.MOON_SHAPES), PhaseCycle(24000, 0))

    fun skyOf(vararg bodies: CelestialBody) = SkySpec(bodies.toList(), StarField(1500, 0L))

    fun starlitAt(sky: SkySpec, dayTime: Long): Float =
        LevelDaylight.starlitnessOf(SkyReading.of(sky, dayTime), SkyRules.DEFAULT)

    test("an ordinary sky puts its stars out at night and takes them in by day") {
        val sky = skyOf(sun(Orbit.VANILLA_SUN), moon(Orbit.VANILLA_MOON))

        check(starlitAt(sky, midnight) == 1.0f) { "midnight gave ${starlitAt(sky, midnight)} of the stars" }
        check(starlitAt(sky, noon) == 0.0f) { "noon gave ${starlitAt(sky, noon)} of the stars" }
    }

    /**
     * **The case this was written for.** A sky with nothing lighting it is never lit, so the stars stand at
     * every hour — where the timeline track said "it is noon" and took them in.
     */
    test("a sky with no sun keeps its stars at every hour") {
        val sunless = skyOf(moon(Orbit.VANILLA_MOON))
        for (hour in listOf(dawn, noon, dusk, midnight)) {
            check(starlitAt(sunless, hour) == 1.0f) { "a sunless sky gave ${starlitAt(sunless, hour)} at $hour" }
        }

        // And an empty sky, which is what a sealed one resolves to — no bodies at all rather than no suns.
        val empty = skyOf()
        check(starlitAt(empty, noon) == 1.0f) { "an empty sky gave ${starlitAt(empty, noon)} at noon" }
    }

    /**
     * **And a moon is not a sun**, which the whole rule rests on: a full moon standing overhead must not
     * take the stars in with it.
     */
    test("a moon overhead does not put the stars out") {
        val moonlit = skyOf(moon(Orbit.VANILLA_SUN))
        check(starlitAt(moonlit, noon) == 1.0f) {
            "a moon at its highest gave ${starlitAt(moonlit, noon)}, so a phase is not being read"
        }
    }

    /**
     * **Several suns hold the stars off**, for the same reason they hold the night off: the ramp follows
     * whichever stands highest, so a sky with a sun always up is a sky whose stars never come.
     */
    test("a second sun opposite the first leaves no hour for stars") {
        val alwaysDay = skyOf(sun(Orbit.VANILLA_SUN), sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 180.0f)))
        val darkest = listOf(dawn, noon, dusk, midnight).maxOf { starlitAt(alwaysDay, it) }
        check(darkest == 0.0f) { "a sky with a sun always up still reached $darkest of its stars" }

        // The control: one of those suns alone does give a night, so the pair is what closed it.
        val oneOfThem = skyOf(sun(Orbit.VANILLA_SUN))
        check(starlitAt(oneOfThem, midnight) == 1.0f) { "one sun alone left no night for the pair to close" }
    }

    /**
     * **A sun that never sets holds the light, whatever else is in the sky.** Walked as "polar sun, and
     * night still fell" (Jonah, 2026-08-27) — which turned out to be a preview never reaching this rule
     * rather than the rule, but the doubt was worth pinning. The ramp follows whichever sun stands
     * highest, so one on vanilla's own path going under at midnight cannot pull the light down with it.
     */
    test("a sun that never sets holds the light, beside one that does") {
        val circling = Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 25.0f)
        val midnightSun = skyOf(sun(Orbit.VANILLA_SUN), sun(circling), moon(Orbit.VANILLA_MOON))
        for (hour in 0L..<24000L step 250L) {
            check(starlitAt(midnightSun, hour) == 0.0f) {
                "at $hour a sky with a sun circling 25° up reached ${starlitAt(midnightSun, hour)} of its " +
                    "stars, so night fell under a midnight sun"
            }
        }
    }

    /** The fade is a ramp and not a switch, or dusk arrives as a flicker. */
    test("the stars come in over the fall of the light rather than at a stroke") {
        val sky = skyOf(sun(Orbit.VANILLA_SUN), moon(Orbit.VANILLA_MOON))
        val overDusk = (12000L..14000L step 250L).map { starlitAt(sky, it) }

        check(overDusk.first() < overDusk.last()) { "the stars did not come in across dusk at all: $overDusk" }
        check(overDusk.any { it > 0.0f && it < 1.0f }) { "dusk is a switch rather than a ramp: $overDusk" }
        check(overDusk.zipWithNext().all { (before, after) -> after >= before }) {
            "the stars went back in partway through dusk: $overDusk"
        }
    }
})
