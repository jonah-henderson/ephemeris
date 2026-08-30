package co.voik.ephemeris.client

import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.phys.Vec3

/**
 * The two softenings that decide how a curtain arrives and leaves — the ring the ground is asked over, and
 * the fade the answer moves along.
 *
 * Asking the ground needs a level and is left to a walk. **Everything about *how* it is asked does not**,
 * and it is the half that fails quietly: a ring that is not a ring gives a border with a direction in it,
 * and a fade that overshoots or never arrives is invisible in a screenshot and plain in a number.
 */
class SnowLineCheck : FunSpec({

    val here = Vec3(100.0, 70.0, -40.0)
    val radius = 24.0

    test("the ring is a ring, and the eye is in the middle of it") {
        val asked = SnowLine.ringAround(here, radius)
        check(asked.first() == here) { "The eye itself was not asked, so standing on a border reads as off it" }
        val around = asked.drop(1)
        check(around.all { Math.abs(it.distanceTo(here) - radius) < 1.0e-6 }) {
            "A point sits ${around.map { it.distanceTo(here) }} from the eye rather than all at $radius"
        }
        check(around.all { it.y == here.y }) { "The ring left the eye's height, so it samples a slope" }
    }

    test("the ring has no direction in it") {
        // Every point pulling the same weight is what makes the border the same width whichever way you
        // cross it. A ring that is not even is a border that is nearer on one side.
        val around = SnowLine.ringAround(here, radius).drop(1)
        val middle = around.reduce(Vec3::add).scale(1.0 / around.size)
        check(middle.distanceTo(here) < 1.0e-6) { "The ring's own middle is $middle rather than the eye" }
    }

    test("a share that is not moving stays where it is") {
        check(SnowLine.faded(0.4f, 0.4f, 1000L) == 0.4f) { "A settled share drifted" }
    }

    test("the fade arrives and does not overshoot") {
        var shown = 0.0f
        // Ten seconds in tenths, which is far longer than anyone stands still on a biome edge.
        repeat(100) { shown = SnowLine.faded(shown, 1.0f, 100L) }
        check(shown > 0.99f) { "After ten seconds the fade had only reached $shown" }
        check(shown <= 1.0f) { "The fade overshot to $shown" }
    }

    test("the fade is most of the way there in a couple of seconds") {
        // The number a walk is judging: cross a biome edge and the sky should have changed its mind by the
        // time you have taken a few more steps, not a quarter of a minute later.
        var shown = 0.0f
        repeat(20) { shown = SnowLine.faded(shown, 1.0f, 100L) }
        check(shown > 0.85f) { "Two seconds after crossing, the curtain was only $shown of the way in" }
    }

    test("the fade goes down as readily as up") {
        var shown = 1.0f
        repeat(100) { shown = SnowLine.faded(shown, 0.0f, 100L) }
        check(shown < 0.01f) { "Ten seconds after leaving the cold the curtain was still $shown" }
        check(shown >= 0.0f) { "The fade undershot to $shown" }
    }

    test("no time passing moves nothing") {
        // Two draws inside one millisecond must not each take a step, or the fade runs at the frame rate.
        check(SnowLine.faded(0.3f, 1.0f, 0L) == 0.3f) { "A fade with no time in it moved anyway" }
    }
})
