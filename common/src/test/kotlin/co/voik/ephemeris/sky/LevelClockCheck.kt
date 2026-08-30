package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction

/**
 * That the hour we hand the timeline is the hour whose sky matches ours.
 *
 * Everything visual about the time of day is keyed to that one number, so an error here is not one wrong
 * colour but every one of them at once — and wrong in a way that reads as "the sky is just broken" rather
 * than as anything a person could point at.
 */
class LevelClockCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    /** Vanilla's own midnight, which is where a sky with nothing to rise sits. */
    val midnight = 18000L

    fun sun(path: CelestialPath) = CelestialBody(path, Appearance.Sprite(Rgba.WHITE, 30.0f, Appearance.SUN_SHAPES))
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

    test("a sun on a stack of motions is followed like any other") {
        // **Walked and found: an epicycling sun stood in a blue sky with the stars out.** The cause was a
        // second ramp — the painter worked out its own nightliness from the suns and vanilla worked out star
        // brightness from the mapped hour, with different numbers, so they disagreed. The painter's copy is
        // gone and vanilla's is the only one; this pins the half that decides it.
        //
        // Every track reads the mapped hour, so agreeing about the *hour* is agreeing about stars, sky
        // colour, fog and light together. A stack is the case that had never been driven through here.
        val epicycling = Motions(
            listOf(
                Motion.Turn(Direction.Axis.Y, Orbit.VANILLAS_NODE),
                Motion.Sweep(Direction.Axis.X, day, pacing = Pacing.EVEN),
                Motion.Sweep(Direction.Axis.Z, day / 3, pacing = Pacing.EVEN),
            ),
        )
        val look = lookOf(sun(epicycling))
        for (tick in 0..<day step 100) {
            val ours = epicycling.altitudeAt(tick.toLong())
            val hour = LevelClock.vanillaEquivalent(look, tick.toLong()) ?: error("no mapping at $tick")
            val litLike = Orbit.VANILLA_SUN.altitudeAt(Math.floorMod(hour, day.toLong()))
            check(Math.abs(ours - litLike) < 1.0f) {
                "At tick $tick an epicycling sun stood ${ours}° up, but the sky was lit like hour " +
                    "${Math.floorMod(hour, day.toLong())}, where vanilla's sun stands ${litLike}° — so the " +
                    "stars come out over a lit sky"
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

    test("a sky with no suns is at midnight at every hour") {
        // **Walked on the Spire** (Jonah, 2026-08-27). Answering null here left every track on the
        // overworld's own schedule, so a world that has never had a sun took vanilla's sky-light colour
        // warm at dusk, its sunrise colour with it, and its light down to nothing at midnight — under a sky
        // whose stars were pinned on and whose fog never moved. There is nothing to derive because there is
        // nothing to rise.
        val moonOnly = lookOf(
            CelestialBody(
                Orbit.VANILLA_MOON,
                Appearance.Sprite(Rgba.WHITE, 20.0f, Appearance.MOON_SHAPES),
                PhaseCycle(day, 0),
            ),
        )
        for (tick in 0..<day step 500) {
            val hour = LevelClock.vanillaEquivalent(moonOnly, tick.toLong()) ?: error("no mapping at $tick")
            check(Math.floorMod(hour, day.toLong()) == midnight) {
                "At tick $tick a sky with no suns was to be lit like hour ${Math.floorMod(hour, day.toLong())}"
            }
        }

        // And an empty sky, which is what a world sealed overhead resolves to.
        val empty = LevelLook(SkySpec(emptyList(), StarField(1500, 0L)))
        check(LevelClock.vanillaEquivalent(empty, 6000L)?.let { Math.floorMod(it, day.toLong()) } == midnight) {
            "An empty sky was lit like noon at noon"
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

    test("the sky never jumps, however many suns and however the hour does") {
        // **The hour is allowed to leap and the sky is not**, and telling those apart is the whole of getting
        // several suns right. When the brightest sun changes, the mapped hour flips from the dusk side of
        // noon to the dawn side — thousands of ticks at once — but it does so at equal heights, so the sky is
        // lit identically either side. Checking the *hour* for continuity would condemn correct behaviour and
        // push toward keeping dusk colours while the sky brightens.
        val skies = mapOf(
            "one sun" to lookOf(sun(Orbit.VANILLA_SUN)),
            "two opposed" to lookOf(sun(Orbit.VANILLA_SUN), sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 180.0f))),
            "two a quarter apart" to
                lookOf(sun(Orbit.VANILLA_SUN), sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 90.0f))),
            "two on different planes" to lookOf(
                sun(Orbit.VANILLA_SUN),
                sun(Orbit.VANILLA_SUN.copy(inclinationDegrees = 50.0f, phaseDegrees = 140.0f)),
            ),
            "three" to lookOf(
                sun(Orbit.VANILLA_SUN),
                sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 120.0f, inclinationDegrees = 25.0f)),
                sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 240.0f, inclinationDegrees = 60.0f)),
            ),
        )
        val step = 20

        for ((name, look) in skies) {
            fun litAsAt(tick: Long): Float {
                val hour = LevelClock.vanillaEquivalent(look, tick) ?: error("$name had no mapping at $tick")
                return Orbit.VANILLA_SUN.altitudeAt(Math.floorMod(hour, day.toLong()))
            }

            var worst = 0.0f
            var worstAt = 0L
            var previous = litAsAt(0)
            for (tick in step..<day step step) {
                val lit = litAsAt(tick.toLong())
                val moved = Math.abs(lit - previous)
                if (moved > worst) {
                    worst = moved
                    worstAt = tick.toLong()
                }
                previous = lit
            }
            // A step of 20 ticks moves vanilla's own sun about this far at its quickest, so anything near it
            // is the sampling rather than a seam.
            check(worst < 1.0f) {
                "'$name' was lit as ${worst}° of sun movement in $step ticks, at tick $worstAt. A single sun " +
                    "shows about 0.4°, so this is a seam in the mapping rather than the sampling"
            }
        }
    }

    test("two opposed suns give two days rather than one") {
        // The semantic worth stating: a sky whose second sun rises as the first sets should run through dawn,
        // noon and dusk twice, and never reach night at all.
        val opposed = lookOf(sun(Orbit.VANILLA_SUN), sun(Orbit.VANILLA_SUN.copy(phaseDegrees = 180.0f)))
        val litAs = (0..<day step 200).map {
            val hour = LevelClock.vanillaEquivalent(opposed, it.toLong()) ?: error("no mapping")
            Orbit.VANILLA_SUN.altitudeAt(Math.floorMod(hour, day.toLong()))
        }
        val noons = litAs.indices.count { at ->
            at > 0 && at < litAs.size - 1 && litAs[at] > litAs[at - 1] && litAs[at] >= litAs[at + 1] &&
                litAs[at] > 60.0f
        }
        check(noons == 2) { "A sky with two opposed suns reached its brightest $noons time(s), not twice" }
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
