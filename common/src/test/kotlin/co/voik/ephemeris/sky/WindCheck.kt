package co.voik.ephemeris.sky

import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** That a level's wind swings only as far as it was told, never jumps, and arrives as it was sent. */
class WindCheck : FunSpec({

    val westerly = Wind(bearingDegrees = 90.0f, strength = 0.5f, wobbleDegrees = 20.0f, seed = 1234L)

    fun degreesAt(wind: Wind, gameTime: Double): Double {
        val blowing = wind.at(gameTime)
        return Math.toDegrees(atan2(blowing.z, blowing.x))
    }

    test("the bearing stays within its wobble") {
        val furthest = (0..2400).maxOf { step -> abs(degreesAt(westerly, step * 100.0) - 90.0) }
        check(furthest <= 20.0 + 1e-6) { "The wind swung $furthest° from its bearing, past its 20° wobble" }
        check(furthest > 5.0) { "The wind swung only $furthest° over ten days, so it hardly wobbles at all" }
    }

    test("the bearing does not jump at the turn of a day") {
        val dusk = degreesAt(westerly, 24000.0 - 0.5)
        val dawn = degreesAt(westerly, 24000.0 + 0.5)
        check(abs(dawn - dusk) < 0.01) { "The wind jumped from $dusk° to $dawn° across midnight" }
    }

    test("it blows a unit vector at the strength it was given") {
        val blowing = westerly.at(5000.0)
        check(abs(hypot(blowing.x, blowing.z) - 1.0) < 1e-9) { "The wind's direction is not a unit vector: $blowing" }
        check(blowing.strength == 0.5f) { "A wind of 0.5 blew at ${blowing.strength}" }
    }

    test("still air is still, and says nothing") {
        check(Wind.STILL.at(1000.0).strength == 0.0f) { "Still air blew" }
        check(LevelLook(SkySpec.VANILLA).saysNothing) { "An ordinary level with still air said something" }
        check(!LevelLook(SkySpec.VANILLA, wind = westerly).saysNothing) { "A level with a wind was taken to say nothing" }
    }

    test("a wind arrives as it was sent") {
        val written = Wind.CODEC.encodeStart(JsonOps.INSTANCE, westerly).getOrThrow()
        val read = Wind.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
        check(read == westerly) { "A wind changed on the way: $read" }
    }
})
