package co.voik.ephemeris

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A creature's texture recoloured toward a colour, in [Oklch] so every pixel keeps its lightness and the picture its
 * shading — `red zombie`, `white wolves`.
 *
 * Toward a colour with a hue, the texture is turned so its own commonest hue lands on that one, which keeps its parts
 * apart from one another (a zombie's skin, shirt and trousers), and its pale pixels are drawn toward that hue by as
 * much as they lack colour, so a mostly white wolf comes out red rather than a wolf with red eyes. Toward white, grey
 * or black, which have no hue, it is drained of colour and its lightness moved toward theirs, keeping its contrast.
 * A pixel nothing can see through — alpha nought — is left as it was.
 */
object Recolouring {

    /** [pixels], packed `0xAARRGGBB`, as a creature drawn in [toward] wears them. */
    fun recoloured(pixels: IntArray, toward: Rgba): IntArray {
        val target = Oklch.of(toward)
        val seen = pixels.filter { alphaOf(it) > 0 }.map { Oklch.of(Rgba.of(it)) }
        if (seen.isEmpty()) return pixels.copyOf()
        val change: (Oklch) -> Oklch = if (target.chroma < HAS_A_HUE) {
            drainedToward(target, seen)
        } else {
            turnedToward(target, seen)
        }
        return IntArray(pixels.size) { at ->
            val pixel = pixels[at]
            val alpha = alphaOf(pixel)
            if (alpha == 0) pixel else change(Oklch.of(Rgba.of(pixel))).toRgba(alpha / FULL).packed()
        }
    }

    private fun turnedToward(target: Oklch, seen: List<Oklch>): (Oklch) -> Oklch {
        val turn = target.hue - (commonestHueOf(seen) ?: target.hue)
        return { own ->
            val pale = 1.0f - (own.chroma / VIVID_CHROMA).coerceAtMost(1.0f)
            val ofTheTarget = Oklch(own.lightness, target.chroma * PALE_CHROMA_SHARE, target.hue)
            own.turned(turn).towards(ofTheTarget, pale * PALE_DRAWN)
        }
    }

    private fun drainedToward(target: Oklch, seen: List<Oklch>): (Oklch) -> Oklch {
        val ownLightness = seen.map { it.lightness }.average().toFloat()
        val moved = (target.lightness - ownLightness) * LIGHTNESS_MOVED
        return { own -> Oklch((own.lightness + moved).coerceIn(0.0f, 1.0f), own.chroma * CHROMA_LEFT_DRAINED, own.hue) }
    }

    /** The hue most of [seen]'s colour is in — each pixel's hue weighted by its chroma — or null where there is none. */
    private fun commonestHueOf(seen: List<Oklch>): Float? {
        var across = 0.0
        var along = 0.0
        for (pixel in seen) {
            val angle = pixel.hue * FULL_TURN_RADIANS
            along += pixel.chroma * cos(angle)
            across += pixel.chroma * sin(angle)
        }
        if (along * along + across * across < NO_HUE_TO_SPEAK_OF) return null
        val turns = atan2(across, along) / FULL_TURN_RADIANS
        return (turns - kotlin.math.floor(turns)).toFloat()
    }

    private fun alphaOf(pixel: Int): Int = (pixel ushr ALPHA_SHIFT) and BYTE

    /** Below this chroma a colour has no hue worth turning toward: white, grey and black. */
    private const val HAS_A_HUE = 0.03f

    /** The chroma a pixel counts as fully coloured at, and so is only turned, not drawn toward the target. */
    private const val VIVID_CHROMA = 0.1f

    /** How far a fully pale pixel is drawn toward the target, and at what share of the target's chroma. */
    private const val PALE_DRAWN = 0.85f
    private const val PALE_CHROMA_SHARE = 0.8f

    /** How far a drained texture's lightness moves toward the target's, and how much colour it keeps. */
    private const val LIGHTNESS_MOVED = 0.7f
    private const val CHROMA_LEFT_DRAINED = 0.1f

    private const val NO_HUE_TO_SPEAK_OF = 1.0e-6
    private const val FULL_TURN_RADIANS = 2.0 * Math.PI
    private const val ALPHA_SHIFT = 24
    private const val BYTE = 0xFF
    private const val FULL = 255.0f
}
