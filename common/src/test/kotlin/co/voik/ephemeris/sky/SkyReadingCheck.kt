package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec

/**
 * What a sky says about the level under it.
 *
 * The interesting cases are the ones vanilla never had: several suns disagreeing about whether it is day,
 * and a sun that never sets — where "when does it next rise" has no answer at all and must say so rather
 * than looping or guessing.
 */
class SkyReadingCheck : FunSpec({

    val noon = 6000L
    val midnight = 18000L

    fun sun(orbit: Orbit) = CelestialBody(orbit, Appearance.Sprite(Rgba.WHITE, 30.0f, Appearance.SUN_SHAPES))
    fun moon(orbit: Orbit) =
        CelestialBody(orbit, Appearance.Sprite(Rgba.WHITE, 20.0f, Appearance.MOON_SHAPES), PhaseCycle(24000, 0))

    fun skyOf(vararg bodies: CelestialBody) = SkySpec(bodies.toList(), StarField(1500, 0L))

    test("one ordinary sun gives ordinary day and night") {
        val sky = skyOf(sun(Orbit.VANILLA_SUN), moon(Orbit.VANILLA_MOON))

        check(SkyReading.of(sky, noon).isDaytime(SkyRules.DEFAULT) == true) { "Noon did not read as daytime" }
        check(SkyReading.of(sky, midnight).isDaytime(SkyRules.DEFAULT) == false) {
            "Midnight did not read as night"
        }
    }

    test("a moon is not a sun, however high it stands") {
        // The definition the whole rule rests on: a body with a phase is a moon, and a full moon overhead
        // must not make it daytime.
        val sky = skyOf(moon(Orbit.VANILLA_SUN))
        val atNoon = SkyReading.of(sky, noon)

        check(atNoon.suns.isEmpty()) { "A phased body was counted among the suns" }
        check(atNoon.isDaytime(SkyRules.DEFAULT) == false) {
            "A moon standing overhead made it daytime, so `isSun` is not reading the phase"
        }
    }

    test("a second sun holds the night off") {
        // Half a turn behind, so it is up exactly when the first is not — the level never has a night.
        val sky = skyOf(sun(Orbit.VANILLA_SUN), sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 180.0f)))

        val everyHour = (0..<24000 step 1000).map { SkyReading.of(sky, it.toLong()).isDaytime(SkyRules.DEFAULT) }
        check(everyHour.all { it == true }) {
            "A sky with two opposed suns had ${everyHour.count { it != true }} hours of night in it"
        }
    }

    test("a named primary sun decides alone") {
        // The same sky, read by the rule that says only the first sun counts: the night comes back.
        val sky = skyOf(sun(Orbit.VANILLA_SUN), sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 180.0f)))
        val rules = SkyRules(daylight = Daylight.PRIMARY_SUN, primaryBody = 0)

        check(SkyReading.of(sky, noon).isDaytime(rules) == true) { "Primary sun at noon did not read as day" }
        check(SkyReading.of(sky, midnight).isDaytime(rules) == false) {
            "The companion sun kept it daytime even though the rules named the first one"
        }
    }

    test("a primary index past the end falls back rather than throwing") {
        val sky = skyOf(sun(Orbit.VANILLA_SUN))
        val rules = SkyRules(daylight = Daylight.PRIMARY_SUN, primaryBody = 7)
        check(SkyReading.of(sky, noon).isDaytime(rules) == true) {
            "A primary index past the end did not fall back to the first body"
        }
    }

    test("the vanilla clock declines to answer, rather than answering wrongly") {
        // Null is the whole point: it means "not mine to say", which is what leaves vanilla's own rules
        // untouched. A `false` here would put a permanently-lit level into permanent night.
        val sky = skyOf(sun(Orbit.VANILLA_SUN))
        val rules = SkyRules(daylight = Daylight.VANILLA_CLOCK)
        check(SkyReading.of(sky, noon).isDaytime(rules) == null) {
            "VANILLA_CLOCK answered the question instead of declining it"
        }
    }

    test("a sun that never sets makes permanent day") {
        val sky = skyOf(sun(Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 30.0f)))
        val everyHour = (0..<24000 step 500).map { SkyReading.of(sky, it.toLong()).isDaytime(SkyRules.DEFAULT) }
        check(everyHour.all { it == true }) {
            "A sun circling 30° up had ${everyHour.count { it != true }} hours of night under it"
        }
    }

    test("a crossing is found, and reported the right way round") {
        val crossing = HorizonCrossing.next(Orbit.VANILLA_SUN, noon)
        check(crossing != null) { "Vanilla's sun was reported as never crossing the horizon" }
        check(!crossing.rising) { "The first crossing after noon was a rise, but the sun should be setting" }
        check(Orbit.VANILLA_SUN.altitudeAt(crossing.dayTime - 2) >= 0.0f) {
            "Two ticks before the reported set, the sun was already below the horizon"
        }
        check(Orbit.VANILLA_SUN.altitudeAt(crossing.dayTime + 2) < 0.0f) {
            "Two ticks after the reported set, the sun was still above the horizon"
        }

        val next = HorizonCrossing.next(Orbit.VANILLA_SUN, crossing.dayTime + 1)
        check(next != null && next.rising) { "The crossing after a set was not a rise" }
    }

    test("a sun that never sets has no next crossing, and says so") {
        val polar = Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 30.0f)
        check(HorizonCrossing.next(polar, 0) == null) {
            "A sun that never sets was given a crossing time, which would be a sunset nobody can watch"
        }

        val buried = Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = -30.0f)
        check(HorizonCrossing.next(buried, 0) == null) { "A sun that never rises was given a crossing time" }
    }

    test("the glow weighting peaks on the horizon and fades either side") {
        val sky = skyOf(sun(Orbit.VANILLA_SUN))
        fun nearnessAt(dayTime: Long) = SkyReading.of(sky, dayTime).suns.single().horizonNearness

        val setting = HorizonCrossing.next(Orbit.VANILLA_SUN, noon)?.dayTime ?: error("no sunset")
        check(nearnessAt(setting) > 0.98f) { "On the horizon the glow weight was only ${nearnessAt(setting)}" }
        check(nearnessAt(noon) == 0.0f) { "At noon the glow weight was ${nearnessAt(noon)}, not nothing" }
        check(nearnessAt(midnight) == 0.0f) { "At midnight the glow weight was ${nearnessAt(midnight)}" }
        // And it is symmetric about the crossing — the sky is reddest just *after* the sun has gone.
        val before = nearnessAt(setting - 400)
        val after = nearnessAt(setting + 400)
        check(before > 0.0f && after > 0.0f) {
            "The glow died the instant the sun crossed ($before before, $after after), so there would be no " +
                "afterglow at all"
        }
    }
})
