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

    test("the sun rises in the east and sets in the west") {
        // The check that would have caught measuring the bearing from south: a sweep test passes either way,
        // and a hundred and eighty degrees out puts every sunset in the wrong quarter of the sky.
        fun bearingWhenCrossing(from: Long): Float {
            val crossing = HorizonCrossing.next(Orbit.VANILLA_SUN, from) ?: error("vanilla's sun never crosses")
            return Orbit.VANILLA_SUN.azimuthAt(crossing.dayTime)
        }

        val sunset = bearingWhenCrossing(noon)
        check(sunset > 225.0f && sunset < 315.0f) {
            "Vanilla's sun set at a bearing of $sunset°, which is not the west. 90 is east and 270 west; a " +
                "reading 180° out means the bearing is being measured from south"
        }

        val sunrise = bearingWhenCrossing(midnight)
        check(sunrise > 45.0f && sunrise < 135.0f) {
            "Vanilla's sun rose at a bearing of $sunrise°, which is not the east"
        }
    }

    test("vanilla's sun is only ever due east or due west, and a tilted one is not") {
        // **This is why vanilla can place its sunrise glow with a coin toss.** Vanilla's sun goes through the
        // zenith, so it has no bearing to sweep: it climbs due east, crosses the top, and descends due west,
        // and `sin(sunAngle) < 0 ? 180 : 0` is a complete description of where its light comes from. Tip the
        // path at all and that stops being true, which is the whole reason the glow needed taking over.
        // Overhead is excluded because a bearing there is not merely unstable but undefined — `atan2` of two
        // numbers that are both nearly zero. It costs nothing to skip: a body that near the zenith is far
        // outside any glow's reach anyway.
        val vanillas = (0..<Orbit.TICKS_PER_VANILLA_DAY step 10)
            .filter { Math.abs(Orbit.VANILLA_SUN.altitudeAt(it.toLong())) < 85.0f }
            .map { Orbit.VANILLA_SUN.azimuthAt(it.toLong()) }
        val awayFromTheMeridian = vanillas.filter { Math.abs(it - 90.0f) > 1.0f && Math.abs(it - 270.0f) > 1.0f }
        check(awayFromTheMeridian.isEmpty()) {
            "Vanilla's sun was found at ${awayFromTheMeridian.size} bearings other than due east or due west, " +
                "which it cannot be: it passes through the zenith"
        }

        val tilted = Orbit.VANILLA_SUN.copy(inclinationDegrees = 55.0f)
        val swept = (0..<Orbit.TICKS_PER_VANILLA_DAY step 10).map { tilted.azimuthAt(it.toLong()) }
        val distinctQuarters = swept.map { (it / 90.0f).toInt() }.distinct()
        check(distinctQuarters.size >= 3) {
            "A sun tilted 55° only ever appeared in ${distinctQuarters.size} quarter(s) of the compass. It " +
                "should wander, and if it does not then a per-sun glow has nothing to place"
        }
    }
})
