package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec

/**
 * The shape of a path, and the two things about it that surprise everyone who reads the code.
 *
 * **A great circle is always half above the horizon.** That is a property of every circle centred on the
 * observer, not of vanilla's sun, so no arrangement of inclination and ascending node can produce a sun that
 * does not set. `Orbit.liftDegrees` makes the path a *small* circle instead, and that is the only thing that
 * can.
 *
 * **Half the circle is not half the day.** Vanilla eases the sweep so a body lingers near the horizon and
 * hurries through the zenith, which leaves the sun up for rather more than half the time. Both are pinned
 * here, because either one read as the other sends you looking for a bug in the geometry.
 */
class OrbitCheck : FunSpec({

    /** Day time 6000 is noon and 18000 midnight — vanilla's convention, and easy to get backwards. */
    val noon = 6000L
    val midnight = 18000L

    fun acrossTheDay(orbit: Orbit): List<Float> =
        (0..<Orbit.TICKS_PER_VANILLA_DAY step 10).map { orbit.altitudeAt(it.toLong()) }

    fun aroundTheCircle(orbit: Orbit): List<Float> =
        (0..<720).map { orbit.altitudeAtProgress(it / 720.0f) }

    test("vanilla's sun is above the horizon for half its circle") {
        val share = aroundTheCircle(Orbit.VANILLA_SUN).count { it >= 0.0f } / 720.0
        check(share > 0.49 && share < 0.51) {
            "Vanilla's sun stood above the horizon for $share of its circle. A great circle centred on the " +
                "observer is half above it by construction, so this is the geometry being wrong rather than " +
                "a tuning matter"
        }
    }

    test("but it is up for rather more than half the day, because the sweep is eased") {
        val share = acrossTheDay(Orbit.VANILLA_SUN).count { it >= 0.0f } / 2400.0
        check(share > 0.53 && share < 0.60) {
            "Vanilla's sun was up for $share of the day. It should be noticeably more than half: the easing " +
                "in `progressAt` makes a body linger near the horizon, so equal arcs take unequal time. If " +
                "this has become 0.5, the easing has been dropped and every body now moves at a constant rate"
        }
    }

    test("vanilla's sun stands overhead at noon and underfoot at midnight") {
        val atNoon = Orbit.VANILLA_SUN.altitudeAt(noon)
        val atMidnight = Orbit.VANILLA_SUN.altitudeAt(midnight)

        check(atNoon > 89.0f) { "At noon vanilla's sun stood at $atNoon°, not overhead" }
        check(atMidnight < -89.0f) { "At midnight vanilla's sun stood at $atMidnight°, not underfoot" }
    }

    test("no tilt short of the degenerate one can keep a sun up") {
        // Inclination 90 is excluded on purpose: it lays the path flat *into* the horizon plane, where every
        // point is on the horizon and "stays up" is true for an uninteresting reason. Every tilt short of
        // that is a genuine great circle and must set.
        val tilted = (0..80 step 10).flatMap { inclination ->
            (0..350 step 30).map { node ->
                Orbit.VANILLA_SUN.copy(
                    inclinationDegrees = inclination.toFloat(),
                    ascendingNodeDegrees = node.toFloat(),
                )
            }
        }
        val stayingUp = tilted.filter { it.staysUp }
        check(stayingUp.isEmpty()) {
            "${stayingUp.size} tilted great circles were reported as never setting, which cannot be true. " +
                "The first was ${stayingUp.firstOrNull()}"
        }
    }

    test("vanilla's path fully lifted puts the sun on the horizon all day") {
        // The case that reads as a mistake until you see why: lift walks a body toward its path's pole, and
        // vanilla's pole is horizontal, so full lift lands the sun *on* the horizon rather than above it.
        val onTheHorizon = Orbit.VANILLA_SUN.copy(liftDegrees = 90.0f)

        check(onTheHorizon.staysUp) {
            "A fully lifted vanilla path set: its lowest point was ${onTheHorizon.lowest}°"
        }
        val extremes = aroundTheCircle(onTheHorizon)
        check(extremes.all { Math.abs(it) < 0.01f }) {
            "A fully lifted vanilla path should sit on the horizon all day, but ranged " +
                "${extremes.min()}°..${extremes.max()}°"
        }
    }

    test("a path laid flat circles at whatever height it is lifted to") {
        // The midnight sun: inclination 90 puts the path's pole at the zenith, and lift is then simply height.
        for (height in listOf(10, 35, 70)) {
            val circling = Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = height.toFloat())
            val altitudes = aroundTheCircle(circling)
            check(altitudes.all { Math.abs(it - height) < 0.01f }) {
                "A flat path lifted to $height° should circle at exactly that height all day, but ranged " +
                    "${altitudes.min()}°..${altitudes.max()}°"
            }
            check(circling.staysUp) { "A sun circling at $height° was reported as setting" }
        }
    }

    test("a sunken path never rises") {
        val buried = Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = -30.0f)
        check(buried.neverRises) { "A path circling 30° under the horizon reached ${buried.highest}°" }
    }

    test("zero lift changes nothing at all") {
        // Guards the composition order rather than the value: applying the lift anywhere but last turns a
        // small circle back into a great one, and at zero that mistake is invisible.
        val moved = acrossTheDay(Orbit.VANILLA_SUN)
            .zip(acrossTheDay(Orbit.VANILLA_SUN.copy(liftDegrees = 0.0f)))
            .filter { (plain, lifted) -> Math.abs(plain - lifted) > 0.0001f }
        check(moved.isEmpty()) { "${moved.size} samples moved when the lift was written down as zero" }
    }

    test("a partly lifted sun still moves, so a polar day is not a frozen one") {
        val polar = Orbit.VANILLA_SUN.copy(inclinationDegrees = 70.0f, liftDegrees = 45.0f)
        val altitudes = acrossTheDay(polar)
        check(altitudes.max() - altitudes.min() > 1.0f) {
            "A lifted sun swung only ${altitudes.max() - altitudes.min()}° across the whole day, which would " +
                "read as pinned to the sky rather than circling — the sweep has to survive the lift"
        }
    }

    test("the sun's bearing sweeps the compass over a day") {
        val bearings = acrossTheDay(Orbit.VANILLA_SUN).indices.map {
            Orbit.VANILLA_SUN.azimuthAt(it * 10L)
        }
        check(bearings.max() - bearings.min() > 180.0f) {
            "The sun's bearing only covered ${bearings.max() - bearings.min()}° across a day, so a horizon " +
                "glow placed by it would sit in roughly one spot"
        }
    }
})
