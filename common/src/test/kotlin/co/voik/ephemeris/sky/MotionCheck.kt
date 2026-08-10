package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction

/**
 * That the ergonomic tier and the general one are the same thing.
 *
 * **`Orbit` builds a motion stack and evaluates through it**, so a circle is provably a special case rather
 * than a second implementation that could drift from the first. The rest of this holds the shapes a stack
 * can make that a circle cannot, because those are the whole reason it exists.
 */
class MotionCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    fun acrossTheDay(path: CelestialPath): List<Float> =
        (0..<day step 10).map { path.altitudeAt(it.toLong()) }

    test("an orbit and the motions it is made of agree everywhere") {
        // If these ever part, the convenient way to say "a circle" has stopped meaning the general way, and
        // every shape built on the stack is being drawn by different maths from the one everybody uses.
        val orbits = listOf(
            Orbit.VANILLA_SUN,
            Orbit.VANILLA_MOON,
            Orbit.VANILLA_SUN.copy(inclinationDegrees = 37.0f, ascendingNodeDegrees = 140.0f),
            Orbit.VANILLA_SUN.copy(liftDegrees = 62.0f),
            Orbit.VANILLA_SUN.copy(retrograde = true, phaseDegrees = 95.0f),
            Orbit.VANILLA_SUN.copy(periodTicks = 51_000, pacing = Pacing.EVEN),
        )
        for (orbit in orbits) {
            val asStack = Motions(orbit.motions, orbit.distance)
            val apart = acrossTheDay(orbit).zip(acrossTheDay(asStack))
                .map { (fromOrbit, fromStack) -> Math.abs(fromOrbit - fromStack) }
                .max()
            check(apart < 0.001f) { "$orbit and its own motion stack disagreed by up to $apart°" }
        }
    }

    test("vanilla's sun is still vanilla's sun") {
        // The property everything else is a departure from, restated after the rewrite: noon overhead,
        // midnight underfoot, and up for rather more than half the day because the sweep is eased.
        check(Orbit.VANILLA_SUN.altitudeAt(6000) > 89.0f) { "Noon is no longer overhead" }
        check(Orbit.VANILLA_SUN.altitudeAt(18000) < -89.0f) { "Midnight is no longer underfoot" }

        val share = acrossTheDay(Orbit.VANILLA_SUN).count { it >= 0.0f } / 2400.0
        check(share > 0.53 && share < 0.60) {
            "Vanilla's sun was up for $share of the day. Rather more than half is what the easing gives; " +
                "exactly half would mean `Pacing.VANILLAS` has stopped being applied"
        }
    }

    test("an even pace really is even, and vanilla's is not") {
        val even = Orbit.VANILLA_SUN.copy(pacing = Pacing.EVEN)
        check(acrossTheDay(even).count { it >= 0.0f } / 2400.0 in 0.48..0.52) {
            "An evenly paced sun was not up for half the day, so `Pacing.EVEN` is doing something to the rate"
        }
    }

    test("a stack can make a shape no circle can: a body that wanders the compass twice a day") {
        // Two sweeps at a two-to-one ratio — the epicycle case, and the point of the whole exercise. A
        // circle has one sweep and can only ever cross the meridian twice; this does more.
        val epicycle = Motions(
            listOf(
                Motion.Turn(Direction.Axis.Y, -90.0f),
                Motion.Sweep(Direction.Axis.X, day, pacing = Pacing.EVEN),
                Motion.Sweep(Direction.Axis.Z, day / 2, pacing = Pacing.EVEN),
            ),
        )
        val quarters = (0..<day step 100).map { (epicycle.bearingAt(it.toLong()) / 90.0f).toInt() }.distinct()
        check(quarters.size == 4) {
            "An epicycling body only visited ${quarters.size} quarters of the compass, so the second sweep " +
                "is not moving it"
        }
    }

    test("an oscillation wobbles and does not circle") {
        // The difference between the two motions, stated: a sweep goes all the way round, an oscillation
        // goes a little way and comes back. A stack of only oscillations must stay near where it started.
        val wobbling = Motions(
            listOf(
                Motion.Turn(Direction.Axis.Y, -90.0f),
                Motion.Oscillate(Direction.Axis.X, amplitudeDegrees = 12.0f, period = day),
            ),
        )
        val altitudes = acrossTheDay(wobbling)
        check(altitudes.min() > 90.0f - 13.0f) {
            "A body wobbling 12° about the zenith fell to ${altitudes.min()}°, so the oscillation is " +
                "sweeping rather than swinging"
        }
        check(wobbling.staysUp) { "A body wobbling near the zenith was reported as setting" }
    }

    test("a stack reports the longest period it contains") {
        val mixed = Motions(
            listOf(
                Motion.Sweep(Direction.Axis.X, 1000),
                Motion.Oscillate(Direction.Axis.Z, 5.0f, 77_000),
                Motion.Turn(Direction.Axis.Y, 30.0f),
            ),
        )
        check(mixed.periodTicks == 77_000) {
            "A mixed stack reported a period of ${mixed.periodTicks}, not the longest motion in it"
        }
    }

    test("a stack of nothing but fixed turns still has a period, and does not move") {
        // Guards a divide by zero as much as anything: a still body has no period to walk, and `swingOf`
        // has to walk something.
        val still = Motions(listOf(Motion.Turn(Direction.Axis.Z, 30.0f)))
        check(still.periodTicks > 0) { "A still path reported a period of ${still.periodTicks}" }
        val altitudes = acrossTheDay(still)
        check(altitudes.max() - altitudes.min() < 0.001f) {
            "A path of only fixed turns moved by ${altitudes.max() - altitudes.min()}°"
        }
    }

    test("a named path falls back rather than throwing when nothing registered it") {
        val missing = Named(net.minecraft.resources.Identifier.fromNamespaceAndPath("nobody", "nothing"))
        check(missing.altitudeAt(6000) > 89.0f) {
            "An unregistered path did not fall back to vanilla's, so a client without the mod that " +
                "registered it would fail to draw a frame rather than draw the wrong sky"
        }
    }

    test("a registered path is the one that is used") {
        val id = net.minecraft.resources.Identifier.fromNamespaceAndPath("ephemeris", "test/flat")
        CelestialPaths.register(id, Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 20.0f))
        val named = Named(id)
        check(named.staysUp) { "A registered polar path was reported as setting through its name" }
        check(Math.abs(named.altitudeAt(0) - 20.0f) < 0.01f) {
            "A registered path circling at 20° read ${named.altitudeAt(0)}° through its name"
        }
    }

    test("risingAt puts the sun where it says") {
        for (bearing in listOf(45.0f, 90.0f, 135.0f)) {
            val path = Orbit.risingAt(bearing)
            val rise = HorizonCrossing.next(path, 18000) ?: error("a circle that never rises")
            val where = path.bearingAt(rise.dayTime)
            val off = Math.abs(((where - bearing + 540.0f) % 360.0f) - 180.0f)
            check(off < 2.0f) { "A sun asked to rise at $bearing° rose at $where°" }
        }
    }
})
