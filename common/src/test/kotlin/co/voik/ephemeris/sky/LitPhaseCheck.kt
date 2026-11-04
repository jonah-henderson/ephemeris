package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec

/**
 * A moon lit by its sun: new beside it, full opposite, waxing as it draws away and waning as it comes back, in
 * vanilla's shape order (0 full, 4 new).
 */
class LitPhaseCheck : FunSpec({

    val day = 24000L
    val month = 8 * day
    val noon = 6000L
    val full = 0
    val new = 4
    val lit = PhaseCycle(periodTicks = month.toInt(), offsetTicks = 0, litBySun = true)

    /** A moon on the sun's own path that falls one turn behind it a month, starting beside it. */
    val trailing = Orbit.VANILLA_SUN.copy(
        distance = Orbit.MOON_DISTANCE,
        periodTicks = (1.0 / (1.0 / day - 1.0 / month)).toInt(),
    )

    test("beside the sun a moon is new, and opposite it full") {
        val beside = Orbit.VANILLA_SUN.copy(distance = Orbit.MOON_DISTANCE)
        check(lit.stepAt(noon, beside, Orbit.VANILLA_SUN) == new) {
            "A moon standing on the sun showed ${lit.stepAt(noon, beside, Orbit.VANILLA_SUN)}"
        }
        check(lit.stepAt(noon, Orbit.VANILLA_MOON, Orbit.VANILLA_SUN) == full) {
            "A moon opposite the sun showed ${lit.stepAt(noon, Orbit.VANILLA_MOON, Orbit.VANILLA_SUN)}"
        }
    }

    test("over a month a moon waxes, comes full, and wanes back to new") {
        val shapes = (0L..8L).map { lit.stepAt(it * day + noon, trailing, Orbit.VANILLA_SUN) }

        check(shapes.first() == new && shapes.last() == new) { "The month did not begin and end new: $shapes" }
        check(shapes[4] == full) { "Halfway through the month the moon was not full: $shapes" }
        check(shapes.subList(1, 4).all { it in 5..7 }) { "The first half was not waxing: $shapes" }
        check(shapes.subList(5, 8).all { it in 1..3 }) { "The second half was not waning: $shapes" }
    }

    test("with no sun, or not lit by one, a moon keeps its cycle") {
        val atNoon = lit.stepAt(noon)
        check(lit.stepAt(noon, trailing, sun = null) == atNoon) { "A moon in a sunless sky left its cycle" }

        val cycling = lit.copy(litBySun = false)
        check(cycling.stepAt(noon, trailing, Orbit.VANILLA_SUN) == cycling.stepAt(noon)) {
            "A moon not lit by the sun followed it anyway"
        }
    }

    test("a drawn sky lights every moon but the first") {
        val moons = SkySpec.drawn(suns = 1, moons = 4, starCount = 0, spread = 1.0f, seed = 7L).bodies
            .mapNotNull { it.phase }

        check(!moons.first().litBySun) { "The first moon left the game's month" }
        check(moons.drop(1).all { it.litBySun }) { "A spare moon kept a cycle of its own" }
    }
})
