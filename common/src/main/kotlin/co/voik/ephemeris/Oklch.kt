package co.voik.ephemeris

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A colour as OKLCH — [lightness] as the eye sees it, [chroma] (how vivid), and [hue] in turns round the wheel
 * (`0..1`) — CSS's `oklch()`. Steps of the same size look the same size, so turning a hue here keeps a colour's
 * lightness where turning it in RGB does not: yellow and blue at one lightness look equally light.
 *
 * Built on Björn Ottosson's OKLab (https://bottosson.github.io/posts/oklab/), whose two matrices are below as he
 * published them; `ephemeris:oklch.glsl` is the same in a shader.
 */
data class Oklch(val lightness: Float, val chroma: Float, val hue: Float) {

    /** This colour with its hue turned [turn] of the way round. */
    fun turned(turn: Float): Oklch {
        val turned = hue + turn
        return copy(hue = turned - kotlin.math.floor(turned))
    }

    /** [amount] of the way from this colour to [other], straight across OKLab so the way between looks even. */
    fun towards(other: Oklch, amount: Float): Oklch {
        val angle = hue * FULL_TURN_RADIANS
        val otherAngle = other.hue * FULL_TURN_RADIANS
        val a = chroma * cos(angle) + (other.chroma * cos(otherAngle) - chroma * cos(angle)) * amount
        val b = chroma * sin(angle) + (other.chroma * sin(otherAngle) - chroma * sin(angle)) * amount
        val turns = atan2(b, a) / FULL_TURN_RADIANS
        return Oklch(lightness + (other.lightness - lightness) * amount, sqrt(a * a + b * b), turns - kotlin.math.floor(turns))
    }

    /** This colour in sRGB, its chroma eased down until it fits where it would not, so its lightness and hue hold. */
    fun toRgba(alpha: Float = 1.0f): Rgba {
        fitting(chroma)?.let { return it.copy(alpha = alpha) }
        var fits = 0.0f
        var overflows = chroma
        repeat(GAMUT_SEARCH_STEPS) {
            val between = (fits + overflows) / 2.0f
            if (fitting(between) != null) fits = between else overflows = between
        }
        return (fitting(fits) ?: grey()).copy(alpha = alpha)
    }

    /** The sRGB colour at [withChroma], or null where it falls outside sRGB. */
    private fun fitting(withChroma: Float): Rgba? {
        val angle = hue * FULL_TURN_RADIANS
        val linear = OkLab(lightness, withChroma * cos(angle), withChroma * sin(angle)).toLinearSrgb()
        val outside = linear.any { it < -GAMUT_SLACK || it > 1.0f + GAMUT_SLACK }
        if (outside) return null
        return Rgba(encoded(linear[0]), encoded(linear[1]), encoded(linear[2]))
    }

    private fun grey(): Rgba = OkLab(lightness, 0.0f, 0.0f).toLinearSrgb().let { Rgba(encoded(it[0]), encoded(it[1]), encoded(it[2])) }

    /** OKLab, the rectangular form: lightness and two axes of colour. */
    private class OkLab(val lightness: Float, val a: Float, val b: Float) {
        fun toLinearSrgb(): FloatArray {
            val coneL = cubed(lightness + 0.3963377774f * a + 0.2158037573f * b)
            val coneM = cubed(lightness - 0.1055613458f * a - 0.0638541728f * b)
            val coneS = cubed(lightness - 0.0894841775f * a - 1.2914855480f * b)
            return floatArrayOf(
                4.0767416621f * coneL - 3.3077115913f * coneM + 0.2309699292f * coneS,
                -1.2684380046f * coneL + 2.6097574011f * coneM - 0.3413193965f * coneS,
                -0.0041960863f * coneL - 0.7034186147f * coneM + 1.7076147010f * coneS,
            )
        }

        private fun cubed(value: Float) = value * value * value
    }

    companion object {
        /** [colour] as OKLCH. */
        fun of(colour: Rgba): Oklch {
            val red = decoded(colour.red)
            val green = decoded(colour.green)
            val blue = decoded(colour.blue)
            val coneL = cbrt(0.4122214708f * red + 0.5363325363f * green + 0.0514459929f * blue)
            val coneM = cbrt(0.2119034982f * red + 0.6806995451f * green + 0.1073969566f * blue)
            val coneS = cbrt(0.0883024619f * red + 0.2817188376f * green + 0.6299787005f * blue)
            val lightness = 0.2104542553f * coneL + 0.7936177850f * coneM - 0.0040720468f * coneS
            val a = 1.9779984951f * coneL - 2.4285922050f * coneM + 0.4505937099f * coneS
            val b = 0.0259040371f * coneL + 0.7827717662f * coneM - 0.8086757660f * coneS
            val turns = atan2(b, a) / FULL_TURN_RADIANS
            return Oklch(lightness, sqrt(a * a + b * b), turns - kotlin.math.floor(turns))
        }

        /** sRGB's gamma taken off a channel, to the linear light the matrices work in. */
        private fun decoded(channel: Float): Float =
            if (channel <= SRGB_LINEAR_BELOW) channel / SRGB_LINEAR_SLOPE
            else ((channel + SRGB_OFFSET) / (1.0f + SRGB_OFFSET)).pow(SRGB_GAMMA)

        /** And put back. */
        private fun encoded(linear: Float): Float {
            val held = linear.coerceIn(0.0f, 1.0f)
            val channel = if (held <= SRGB_LINEAR_BELOW / SRGB_LINEAR_SLOPE) held * SRGB_LINEAR_SLOPE
                else (1.0f + SRGB_OFFSET) * held.pow(1.0f / SRGB_GAMMA) - SRGB_OFFSET
            return channel.coerceIn(0.0f, 1.0f)
        }

        // sRGB's transfer curve, as the standard gives it: linear near black, a 2.4 power above.
        private const val SRGB_LINEAR_BELOW = 0.04045f
        private const val SRGB_LINEAR_SLOPE = 12.92f
        private const val SRGB_OFFSET = 0.055f
        private const val SRGB_GAMMA = 2.4f

        private const val FULL_TURN_RADIANS = (2.0 * Math.PI).toFloat()

        /** How finely an out-of-gamut colour's chroma is searched down: a sixty-fourth of it after six halvings. */
        private const val GAMUT_SEARCH_STEPS = 6

        /** Rounding in the matrices leaves a fitting colour a hair outside; this much is let through. */
        private const val GAMUT_SLACK = 0.0005f
    }
}
