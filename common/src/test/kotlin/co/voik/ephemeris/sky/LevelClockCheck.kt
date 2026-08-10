package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec

/**
 * That the hour we hand the timeline is the hour whose sky matches ours.
 *
 * Everything visual about the time of day is keyed to that one number, so an error here is not one wrong
 * colour but every one of them at once — and wrong in a way that reads as "the sky is just broken" rather
 * than as anything a person could point at.
 */
class LevelClockCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    fun sun(orbit: Orbit) = CelestialBody(orbit, Appearance.Sprite(Rgba.WHITE, 30.0f, Appearance.SUN_SHAPES))
    fun lookOf(vararg bodies: CelestialBody, rules: SkyRules = SkyRules.DEFAULT) =
        LevelLook(SkySpec(bodies.toList(), StarField(1500, 0L)), rules = rules)

    test("the hour found really is one where vanilla's sun stands that high") {
        for (height in listOf(-80, -40, -10, 0, 10, 45, 80)) {
            for (rising in listOf(true, false)) {
                val hour = LevelClock.hourAt(height.toFloat(), rising)
                val there = Orbit.VANILLA_SUN.altitudeAt(hour)
                check(Math.abs(there - height) < 1.0f) {
                    "Asked for ${height}° ${if (rising) "climbing" else "falling"}, got hour $hour where " +
                        "vanilla's sun stands ${there}°"
                }
            }
        }
    }

    test("climbing and falling give different hours, on the right sides of noon") {
        // The branch that runs the sky's colours backwards when it is wrong.
        val climbing = LevelClock.hourAt(20.0f, rising = true)
        val falling = LevelClock.hourAt(20.0f, rising = false)
        check(climbing != falling) { "Climbing and falling to 20° gave the same hour, $climbing" }
        check(climbing in 0..6000 || climbing in 18000..<24000) {
            "Climbing to 20° gave hour $climbing, which is not on the way up to noon"
        }
        check(falling in 6000..18000) { "Falling to 20° gave hour $falling, which is not on the way down" }
    }

    test("an ordinary sky is left on the real clock") {
        check(LevelClock.vanillaEquivalent(LevelLook(SkySpec.VANILLA), 6000) == null) {
            "An ordinary sky was given a mapped hour, so vanilla's own sky would be lit by our arithmetic " +
                "rather than by its own keyframes"
        }
    }

    test("a sky told to keep vanilla's clock keeps it") {
        val look = lookOf(
            sun(Orbit.VANILLA_SUN.copy(inclinationDegrees = 40.0f)),
            rules = SkyRules(daylight = Daylight.VANILLA_CLOCK),
        )
        check(LevelClock.vanillaEquivalent(look, 6000) == null) { "VANILLA_CLOCK was overridden anyway" }
    }

    test("a second sun holds the sky in daylight") {
        // The walk's actual complaint: one sun set, night fell, and the other sun was at its peak.
        val opposed = lookOf(
            sun(Orbit.VANILLA_SUN),
            sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 180.0f)),
        )
        for (tick in 0..<day step 500) {
            val hour = LevelClock.vanillaEquivalent(opposed, tick.toLong()) ?: error("no mapping at $tick")
            val litLike = Orbit.VANILLA_SUN.altitudeAt(Math.floorMod(hour, day.toLong()))
            check(litLike > -12.0f) {
                "At tick $tick a sky with two opposed suns was to be lit like hour ${hour % day}, where " +
                    "vanilla's sun stands ${litLike}° — that is night, and one of the two suns is up"
            }
        }
    }

    test("a sun that never sets never brings on night") {
        val polar = lookOf(sun(Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 25.0f)))
        for (tick in 0..<day step 500) {
            val hour = LevelClock.vanillaEquivalent(polar, tick.toLong()) ?: error("no mapping at $tick")
            check(Orbit.VANILLA_SUN.altitudeAt(Math.floorMod(hour, day.toLong())) > 0.0f) {
                "A midnight sun was to be lit like hour ${hour % day}, which is below the horizon"
            }
        }
    }

    test("the day number survives, so anything counting days still counts") {
        // A moon's phase advances on the day count; a mapping that moved it would make the moon flicker.
        val look = lookOf(sun(Orbit.VANILLA_SUN.copy(inclinationDegrees = 30.0f)))
        for (dayNumber in 0..4) {
            val at = dayNumber * day + 9000L
            val hour = LevelClock.vanillaEquivalent(look, at) ?: error("no mapping")
            check(Math.floorDiv(hour, day.toLong()) == dayNumber.toLong()) {
                "On day $dayNumber the mapped hour $hour fell on day ${Math.floorDiv(hour, day.toLong())}"
            }
        }
    }

    test("an ordinary single sun maps to about the hour it already is") {
        // The sanity anchor: a sky that is vanilla's in all but name should be lit at nearly the real hour.
        //
        // The companion is on a *flat* path sunk 80°, which is genuinely always far below. Lifting vanilla's
        // own path instead puts a sun **on the horizon** rather than under it — which is what `liftDegrees`
        // says it does, and which made this fixture's second sun the highest all night on the first attempt.
        val buried = Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = -80.0f)
        val nearlyVanilla = lookOf(sun(Orbit.VANILLA_SUN), sun(buried))
        for (tick in listOf(1000, 6000, 11000, 15000, 20000)) {
            val hour = (LevelClock.vanillaEquivalent(nearlyVanilla, tick.toLong()) ?: error("none")) % day
            val apart = Math.abs(hour - tick)
            val roundTheClock = Math.min(apart, day - apart)
            check(roundTheClock < 400) {
                "At tick $tick a nearly-vanilla sky mapped to hour $hour, which is $roundTheClock ticks away"
            }
        }
    }
})
