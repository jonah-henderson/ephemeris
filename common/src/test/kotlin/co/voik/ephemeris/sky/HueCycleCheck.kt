package co.voik.ephemeris.sky

import co.voik.ephemeris.Oklch
import co.voik.ephemeris.Rgba
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

class HueCycleCheck : FunSpec({

    fun Rgba.isNear(other: Rgba) =
        abs(red - other.red) < 0.01f && abs(green - other.green) < 0.01f && abs(blue - other.blue) < 0.01f

    val red = Rgba(1.0f, 0.0f, 0.0f)

    fun lightnessOf(colour: Rgba) = Oklch.of(colour).lightness

    test("a turn goes red toward green and then blue, and a whole turn back to red") {
        val third = red.hueTurned(1.0f / 3.0f)
        val twoThirds = red.hueTurned(2.0f / 3.0f)
        check(third.green > third.red && third.green > third.blue) { "a third of a turn gave $third" }
        check(twoThirds.blue > twoThirds.red && twoThirds.blue > twoThirds.green) { "two thirds gave $twoThirds" }
        check(red.hueTurned(1.0f).isNear(red)) { "a whole turn gave ${red.hueTurned(1.0f)}" }
    }

    test("a turned colour looks as light as it did, all the way round") {
        val leaf = Rgba(0.35f, 0.6f, 0.2f)
        val turns = (0..11).map { it / 12.0f }
        val drift = turns.maxOf { turn -> abs(lightnessOf(leaf.hueTurned(turn)) - lightnessOf(leaf)) }
        check(drift < 0.01f) { "a leaf's lightness drifted by $drift as it turned" }
    }

    test("a grey has no hue to turn") {
        val grey = Rgba(0.4f, 0.4f, 0.4f)
        check(grey.hueTurned(0.37f).isNear(grey)) { "grey turned to ${grey.hueTurned(0.37f)}" }
    }

    test("OKLCH comes back to the colour it was read from") {
        for (colour in listOf(red, Rgba(0.2f, 0.5f, 0.9f), Rgba(0.9f, 0.85f, 0.1f), Rgba(0.05f, 0.05f, 0.05f), Rgba.WHITE)) {
            val back = Oklch.of(colour).toRgba()
            check(back.isNear(colour)) { "$colour came back as $back" }
        }
    }

    test("a cycle comes round once a period, from where its offset puts it") {
        val cycle = HueCycle(periodTicks = 2400, offset = 0.25f)
        check(abs(cycle.turnAt(0L) - 0.25f) < 0.001f) { "it started at ${cycle.turnAt(0L)}" }
        check(abs(cycle.turnAt(1200L) - 0.75f) < 0.001f) { "half a period on it stood at ${cycle.turnAt(1200L)}" }
        check(abs(cycle.turnAt(2400L * 1000L) - 0.25f) < 0.001f) { "a thousand periods on it stood at ${cycle.turnAt(2400_000L)}" }
    }

    test("two cycles alike turn in step, and two on their own clocks apart") {
        val one = HueCycle(periodTicks = 3000)
        val other = HueCycle(periodTicks = 3000)
        val ownClock = HueCycle(periodTicks = 5000, offset = 0.4f)
        check(one.turnAt(777L) == other.turnAt(777L)) { "alike cycles fell out of step" }
        check(one.turnAt(777L) != ownClock.turnAt(777L)) { "a cycle on its own clock kept step" }
    }

    test("a pale colour takes the turn's hue by its tinge, and a saturated one only turns") {
        val tinged = HueCycle(periodTicks = 2400, tinge = 0.5f)
        val white = tinged.at(Rgba.WHITE, 0.0f)
        check(white.red > white.green && white.red > white.blue) { "white at the start of a tinged turn was $white" }
        check(HueCycle(periodTicks = 2400).at(Rgba.WHITE, 0.3f).isNear(Rgba.WHITE)) { "white turned with no tinge" }
        check(tinged.at(red, 0.3f).isNear(red.hueTurned(0.3f))) { "red was tinged as well as turned" }
        // Half way from white's lightness of one to the pastel's 0.9.
        check(abs(lightnessOf(white) - 0.95f) < 0.02f) { "a white tinged half way stood at lightness ${lightnessOf(white)}" }
    }

    test("the cycles survive the trip to a client") {
        val cycles = ColourCycles(sky = HueCycle(3000), stars = HueCycle(4800, 0.2f, 0.6f), bodies = mapOf(1 to HueCycle(24000)))
        val arrived = ColourCycles.CODEC.parse(JsonOps.INSTANCE, ColourCycles.CODEC.encodeStart(JsonOps.INSTANCE, cycles).getOrThrow())
            .getOrThrow()
        check(arrived == cycles) { "the cycles arrived as $arrived" }
        check(ColourCycles.NONE.isNone && !cycles.isNone) { "whether anything turns was misread" }
    }
})
