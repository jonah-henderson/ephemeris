package co.voik.ephemeris.sky

import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import org.joml.Vector3f

/**
 * That a level's rings stand where their geometry puts them, are lit and shadowed as solid things are, and differ
 * from one seed to the next. Everything here is arithmetic every client does for itself, so it is checked here or
 * nowhere.
 */
class PlanetaryRingsCheck : FunSpec({

    val crown = (Math.PI / 2.0).toFloat()

    test("an Age near the ring plane sees the arch overhead, and one far from it sees it along the horizon") {
        val nearThePlane = PlanetaryRings(latitudeDegrees = 4.0f).crownAltitudeDegrees(PlanetaryRings.ORDINARY_OUTER)
        val ordinary = PlanetaryRings().crownAltitudeDegrees(PlanetaryRings.ORDINARY_OUTER)
        val farFromIt = PlanetaryRings(latitudeDegrees = 58.0f).crownAltitudeDegrees(PlanetaryRings.ORDINARY_OUTER)
        check(nearThePlane > 75.0f) { "Near the ring plane the crown stood at $nearThePlane°, not overhead" }
        check(ordinary in 20.0f..60.0f) { "Ordinary rings crowned at $ordinary°" }
        check(farFromIt in 0.0f..12.0f) { "Far from the ring plane the crown stood at $farFromIt°, not along the horizon" }
    }

    test("the outer edge stands above the inner") {
        val rings = PlanetaryRings()
        check(rings.crownAltitudeDegrees(rings.outerRadius) > rings.crownAltitudeDegrees(rings.innerRadius)) {
            "The outer edge of the rings stood below the inner"
        }
    }

    test("the crown faces its bearing") {
        val east = PlanetaryRings(bearingDegrees = 90.0f).pointAt(crown, 2.0f)
        check(east.x > 0.0f && kotlin.math.abs(east.z) < 1.0e-4f) { "A crown borne east stood at $east" }
        val south = PlanetaryRings().pointAt(crown, 2.0f)
        check(south.z > 0.0f && kotlin.math.abs(south.x) < 1.0e-4f) { "A crown borne south stood at $south" }
    }

    test("at midnight the planet's shadow falls across the crown, and the ends of the arch stay lit") {
        val rings = PlanetaryRings()
        val sunBelow = Vector3f(0.0f, -1.0f, 0.0f)
        val atTheCrown = rings.shadeAt(crown, 2.0f, sunBelow)
        val nearTheHorizon = rings.shadeAt(0.25f, 2.0f, sunBelow)
        check(atTheCrown < 0.1f) { "The crown at midnight was lit $atTheCrown" }
        check(nearTheHorizon > 0.3f) { "The arch's end at midnight was lit only $nearTheHorizon" }
    }

    test("the face toward the Age stays lit from noon to midnight") {
        val rings = PlanetaryRings()
        val atNoon = rings.shadeAt(0.25f, 2.0f, Vector3f(0.0f, 1.0f, 0.0f))
        val atMidnight = rings.shadeAt(0.25f, 2.0f, Vector3f(0.0f, -1.0f, 0.0f))
        check(atNoon > 0.95f) { "The arch's end at noon showed $atNoon" }
        check(atMidnight > 0.9f) { "The arch's end at midnight showed only $atMidnight: the near face went dark" }
    }

    test("no sun leaves them faint") {
        check(PlanetaryRings().shadeAt(crown, 2.0f, null) == PlanetaryRings.UNLIT) { "Rings under no sun were lit" }
    }

    test("the bands and gaps differ from seed to seed, and hold for one") {
        fun profileOf(seed: Long) = (0..200).map { PlanetaryRings(seed = seed).opacityAcross(it / 200.0f) }
        check(profileOf(1L) == profileOf(1L)) { "One seed laid its rings two ways" }
        val distinct = (1L..12L).map(::profileOf).toSet()
        check(distinct.size == 12) { "Twelve seeds laid only ${distinct.size} kinds of rings" }
    }

    test("thin rings are more see-through than dense ones") {
        fun mean(density: Float) = (0..200).map { PlanetaryRings(density = density, seed = 7L).opacityAcross(it / 200.0f) }.average()
        check(mean(0.1f) < mean(0.9f)) { "Thin rings were no more see-through than dense ones" }
    }

    test("rings arrive at the client as they were sent") {
        val rings = PlanetaryRings(glow = 1.4f, latitudeDegrees = 12.0f, density = 0.3f, bearingDegrees = 40.0f, seed = 99L)
        val written = PlanetaryRings.CODEC.encodeStart(JsonOps.INSTANCE, rings).getOrThrow()
        check(PlanetaryRings.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow() == rings) { "Rings changed on the way" }
        val spec = SkySpec.VANILLA.copy(planetaryRings = rings)
        val sent = SkySpec.CODEC.parse(JsonOps.INSTANCE, SkySpec.CODEC.encodeStart(JsonOps.INSTANCE, spec).getOrThrow()).getOrThrow()
        check(sent.planetaryRings == rings) { "A sky lost its rings on the way" }
        check(sent.isOrdinary) { "Rings made an otherwise vanilla sky take over from vanilla" }
    }
})
