package co.voik.ephemeris

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * The colour arithmetic a wound's corruption is built out of (design §5.1).
 *
 * Draining and dimming are two different things doing two different jobs, and the register depends on the
 * difference: **dimming reads as evening and draining reads as wrong.** A place robbed of its colour but
 * not its light is uncanny in a way a dark place simply is not.
 */
class RgbaCheck : FunSpec({

    infix fun Float.about(other: Float) = abs(this - other) < A_HAIR

    test("a colour survives being packed and taken apart again") {
        val colours = listOf(
            Rgba(0.0f, 0.0f, 0.0f),
            Rgba(1.0f, 1.0f, 1.0f),
            Rgba(0.2f, 0.6f, 0.9f),
            Rgba(0.5f, 0.25f, 0.75f, 0.5f),
        )
        for (colour in colours) {
            val there = Rgba.of(colour.packed())
            check(there.red about colour.red && there.green about colour.green && there.blue about colour.blue) {
                "$colour came back as $there"
            }
        }
    }

    /**
     * **Draining keeps the light and takes the colour**, which is the whole distinction from dimming. A
     * fully drained colour is grey at the same brightness, not black.
     */
    test("draining a colour leaves its brightness alone") {
        val blue = Rgba(0.2f, 0.4f, 0.9f)
        val grey = blue.drained(1.0f)
        check(grey.red about grey.green && grey.green about grey.blue) { "fully drained and still coloured: $grey" }
        check(grey.red > 0.0f) { "draining turned the light off rather than the colour: $grey" }
        // And nothing at all is exactly what it was.
        val untouched = blue.drained(0.0f)
        check(untouched.red about blue.red && untouched.blue about blue.blue) { "draining by nothing moved it" }
    }

    /**
     * **The grey is the eye's weighting, not the average of the channels.** Green carries most of what we
     * read as brightness and blue almost none, so a flat mean turns a deep blue into something *lighter*
     * than it was — which reads as the colour being washed out rather than drained.
     */
    test("a drained blue does not come back brighter than it went in") {
        val deepBlue = Rgba(0.0f, 0.0f, 1.0f)
        val grey = deepBlue.drained(1.0f)
        val flatMean = (deepBlue.red + deepBlue.green + deepBlue.blue) / 3.0f
        check(grey.red < flatMean) { "a flat mean was used: ${grey.red} against $flatMean" }
        check(grey.red < deepBlue.blue) { "draining brightened it: $grey" }
    }

    /** Dimming is the other half, and it does take the light. */
    test("dimming takes the light and keeps the hue") {
        val warm = Rgba(0.8f, 0.4f, 0.2f)
        val dark = warm.dimmed(0.5f)
        check(dark.red about 0.4f && dark.green about 0.2f && dark.blue about 0.1f) { "dimmed wrongly: $dark" }
        // The ratios are untouched, which is what makes it read as less light rather than another colour.
        check(dark.red / dark.green about warm.red / warm.green) { "dimming shifted the hue: $dark" }
    }
})

/** Closer than a byte of colour, which is all `packed` can carry anyway. */
private const val A_HAIR = 1f / 255f
