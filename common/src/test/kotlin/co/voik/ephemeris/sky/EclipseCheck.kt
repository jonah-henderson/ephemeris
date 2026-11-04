package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How much of a sun a moon hides. The cases that matter are the ones a player sees: total, annular, partial and
 * none, and the vanilla sky, whose moon must never eclipse anything.
 */
class EclipseCheck : FunSpec({

    val noon = 6000L
    val sunSize = 30.0f
    val moonDistance = Orbit.MOON_DISTANCE

    /** A moon the sun's apparent size exactly: its disc subtends what the sun's does, from nearer. */
    val sameApparentSize = sunSize * moonDistance / Orbit.VANILLA_SUN.distance

    fun sun() = CelestialBody(Orbit.VANILLA_SUN, Appearance.Sprite(Rgba.WHITE, sunSize, Appearance.SUN_SHAPES))

    fun moon(size: Float, phaseDegrees: Float = 0.0f, blending: Blending = Blending.COVERS) = CelestialBody(
        Orbit.VANILLA_SUN.copy(distance = moonDistance, phaseDegrees = phaseDegrees),
        Appearance.Sprite(Rgba.WHITE, size, Appearance.MOON_SHAPES),
        PhaseCycle(24000, 0),
        blending,
    )

    fun skyOf(vararg bodies: CelestialBody) = SkySpec(bodies.toList(), StarField(1500, 0L))

    test("a moon the sun's size, centred on it, hides it wholly") {
        val eclipse = Eclipse.of(skyOf(sun(), moon(sameApparentSize)), noon).single()

        check(eclipse.isTotal) { "A centred moon the sun's apparent size hid only ${eclipse.coverage} of it" }
        check(eclipse.coveredBy == listOf(1)) { "The covering body was ${eclipse.coveredBy}, not the moon" }
    }

    test("a smaller moon, centred, leaves a ring") {
        val eclipse = Eclipse.of(skyOf(sun(), moon(sameApparentSize / 2)), noon).single()

        check(!eclipse.isTotal) { "A moon half the sun's apparent size covered it wholly" }
        check(abs(eclipse.coverage - 0.25f) < 0.01f) {
            "Half the radius should hide a quarter of the disc, and hid ${eclipse.coverage}"
        }
    }

    test("a moon partway across hides part, and one well clear hides nothing") {
        val partway = Eclipse.of(skyOf(sun(), moon(sameApparentSize, phaseDegrees = 3.0f)), noon).single()
        check(partway.coverage > 0.0f && !partway.isTotal) {
            "A moon three degrees along the sun's path hid ${partway.coverage} of it"
        }

        val clear = Eclipse.of(skyOf(sun(), moon(sameApparentSize, phaseDegrees = 20.0f)), noon)
        check(clear.isEmpty()) { "A moon twenty degrees off the sun still covered it: $clear" }
    }

    test("only a body that covers and stands nearer hides a sun") {
        val adding = moon(sameApparentSize, blending = Blending.ADDS)
        check(Eclipse.of(skyOf(sun(), adding), noon).isEmpty()) { "A moon drawn adding hid the sun" }

        val behind = moon(sameApparentSize).copy(path = Orbit.VANILLA_SUN.copy(distance = 110.0f))
        check(Eclipse.of(skyOf(sun(), behind), noon).isEmpty()) { "A moon farther off than the sun hid it" }
    }

    test("vanilla's moon never eclipses vanilla's sun") {
        val sky = SkySpec.drawn(suns = 1, moons = 1, starCount = 0, spread = 0.0f, seed = 0L)
        val eclipsed = (0L..<24000L step 100L).filter { Eclipse.of(sky, it).isNotEmpty() }

        check(eclipsed.isEmpty()) { "The vanilla sky had an eclipse at ${eclipsed.first()}" }
    }

    test("a moon a month slower than the sun eclipses it once a month") {
        val month = 8 * 24000L
        val period = (1.0 / (1.0 / 24000 - 1.0 / month)).toInt()
        val trailing = moon(sameApparentSize * 1.25f).let { it.copy(path = (it.path as Orbit).copy(periodTicks = period)) }
        val passages = Eclipse.passagesBetween(skyOf(sun(), trailing), from = 8000L, until = 2 * month + 8000L)

        check(passages.size == 2) { "Two months held ${passages.size} eclipses: $passages" }
        check(passages.all { it.isTotal && it.coveredBy == listOf(1) }) { "Not every eclipse was the moon's, total" }
        val offMonth = passages.mapIndexed { index, passage -> abs(passage.greatestAt - month * (index + 1)) }
        check(offMonth.all { it < 1000L }) { "The eclipses fell this far from each month's end: $offMonth" }

        fun totalityInside(passage: EclipsePassage): Boolean {
            val from = passage.totalFrom ?: return false
            val until = passage.totalUntil ?: return false
            return passage.begins < from && until < passage.ends
        }
        check(passages.all(::totalityInside)) { "Totality did not sit inside its eclipse: $passages" }
    }

    test("an eclipse darkens the day as far as night at totality, and a graze not at all") {
        fun darkeningWith(moon: CelestialBody): Int {
            val sky = skyOf(sun(), moon)
            val sun = SkyReading.of(sky, noon).suns.single()
            return LevelDaylight.darkeningUnder(sky, sun, noon)
        }

        check(darkeningWith(moon(sameApparentSize)) == 11) { "Totality at noon left ${darkeningWith(moon(sameApparentSize))}" }
        check(darkeningWith(moon(sameApparentSize, phaseDegrees = 20.0f)) == 0) { "A moon well clear darkened noon" }
        val ring = darkeningWith(moon(sameApparentSize * 0.88f))
        check(ring in 1..4) { "A ring of fire darkened noon by $ring, where it should dim it a little" }

        val steps = (0..100).map { LevelDaylight.darkeningCoveredBy(it / 100.0f) }
        check(steps.zipWithNext().all { (less, more) -> more >= less }) { "Darkening fell as coverage grew: $steps" }
        check(steps.take(51).all { it == 0 }) { "Half a sun covered already darkened the day: $steps" }
    }

    test("the last light of a sun about to go dark is on the rim farthest from the moon") {
        val sky = skyOf(sun(), moon(sameApparentSize * 1.25f, phaseDegrees = 1.0f))
        val eclipse = Eclipse.of(sky, noon).single()
        val lastLight = Eclipse.lastLightOf(sky, eclipse, noon) ?: error("a larger moon gave no last light")

        val sunAt = sky.bodies[0].path.directionAt(noon)
        val moonAt = sky.bodies[1].path.directionAt(noon)
        val fromSun = CelestialPath.angleBetween(lastLight.direction, sunAt)
        check(abs(fromSun - lastLight.sunRadius) < 1e-3) { "The last light was $fromSun from the sun's centre, off its rim" }
        check(CelestialPath.angleBetween(lastLight.direction, moonAt) > CelestialPath.angleBetween(sunAt, moonAt)) {
            "The last light was on the moon's side of the sun"
        }

        val smaller = skyOf(sun(), moon(sameApparentSize * 0.8f, phaseDegrees = 1.0f))
        check(Eclipse.lastLightOf(smaller, Eclipse.of(smaller, noon).single(), noon) == null) {
            "A moon too small to cover the sun gave a single last light, where its last light is a ring"
        }
    }

    test("two equal discs a radius apart share the closed form's lens") {
        val radius = 0.05
        val lens = radius * radius * (2 * PI / 3 - sqrt(3.0) / 2)
        val expected = lens / (PI * radius * radius)
        val actual = Eclipse.overlapShare(radius, radius, separation = radius)

        check(abs(actual - expected) < 1e-9) { "Equal discs a radius apart overlapped by $actual, not $expected" }
    }

    test("the share hidden only grows as the discs close") {
        val separations = (0..100).map { it * 0.001 }.reversed()
        val shares = separations.map { Eclipse.overlapShare(0.04, 0.045, it) }

        check(shares.zipWithNext().all { (farther, nearer) -> nearer >= farther }) {
            "Coverage fell somewhere as the moon closed on the sun: $shares"
        }
        check(shares.first() == 0.0 && shares.last() == 1.0) { "The ends were ${shares.first()} and ${shares.last()}" }
    }

    test("an eclipse's look is whole while its sun is up, and gone once it has set") {
        check(Eclipse.lookAt(30.0f) == 1.0f) { "an eclipse high in the sky was not whole" }
        check(Eclipse.lookAt(-30.0f) == 0.0f) { "an eclipse at midnight still showed" }
        val setting = Eclipse.lookAt(-1.0f)
        check(setting > 0.0f && setting < 1.0f) { "an eclipse on the horizon was $setting, not fading" }
    }
})
