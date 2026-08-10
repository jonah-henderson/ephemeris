package co.voik.ephemeris

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * An RGBA colour with channels in `0.0..1.0`. A small, testable value type so rendering code passes named
 * colours around instead of loose float quadruples.
 *
 * **Lives in `common` rather than beside the renderers** because a colour is part of a level's *data*: a
 * celestial body carries its tint through [co.voik.ephemeris.sky.SkySpec], which is codec'd and sent to
 * the client. The `VertexConsumer` helpers that used to share this file stayed on the loader side, where the
 * rendering is.
 */
data class Rgba(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1.0f) {

    /** Linear interpolation toward [other]; [amount] 0 = this, 1 = other. */
    fun lerp(other: Rgba, amount: Float): Rgba = Rgba(
        red + (other.red - red) * amount,
        green + (other.green - green) * amount,
        blue + (other.blue - blue) * amount,
        alpha + (other.alpha - alpha) * amount,
    )

    /**
     * This colour as one `0xAARRGGBB` integer — how vanilla's environment attributes hold a colour.
     *
     * Ours are floats because that is what a renderer multiplies by; packing is the boundary, not the
     * representation.
     */
    fun packed(): Int = (byteOf(alpha) shl 24) or (byteOf(red) shl 16) or (byteOf(green) shl 8) or byteOf(blue)

    private fun byteOf(channel: Float): Int = (channel.coerceIn(0.0f, 1.0f) * FULL).toInt()

    /** This colour at [factor] of its brightness, alpha untouched. */
    fun dimmed(factor: Float): Rgba = Rgba(red * factor, green * factor, blue * factor, alpha)

    /**
     * Toward grey by [amount], keeping the same brightness — colour drained out rather than light taken
     * away, which is a different thing from [dimmed] and reads as *wrong* where dimming reads as evening.
     *
     * The grey is the eye's own weighting of the channels, not their average: green carries most of what
     * we see as brightness and blue almost none, so a flat mean turns a blue sky into something lighter
     * than it was.
     */
    fun drained(amount: Float): Rgba {
        val grey = red * SEEN_AS_RED + green * SEEN_AS_GREEN + blue * SEEN_AS_BLUE
        return lerp(Rgba(grey, grey, grey, alpha), amount)
    }

    /**
     * The other way along the same line: pushed **away** from its own grey, so what colour it has becomes
     * the whole of it.
     *
     * [drained] walks a colour toward the grey it weighs the same as; this walks it past itself, so a
     * muted red becomes a red that means it. A palette chosen to sit behind things — which is what a sky
     * or a fog colour is — reads as a *wash* when it is put on something the eye looks straight at, and a
     * sun is the thing the eye looks straight at.
     */
    fun saturated(amount: Float): Rgba {
        val grey = red * SEEN_AS_RED + green * SEEN_AS_GREEN + blue * SEEN_AS_BLUE
        fun pushed(channel: Float) = (grey + (channel - grey) * (1.0f + amount)).coerceIn(0.0f, 1.0f)
        return Rgba(pushed(red), pushed(green), pushed(blue), alpha)
    }

    companion object {
        val WHITE = Rgba(1.0f, 1.0f, 1.0f)

        private const val FULL = 255f

        // How much of perceived brightness each channel carries — the usual luma weights.
        private const val SEEN_AS_RED = 0.2126f
        private const val SEEN_AS_GREEN = 0.7152f
        private const val SEEN_AS_BLUE = 0.0722f

        /** A packed colour taken apart again — the inverse of [packed], for a value vanilla handed us. */
        fun of(packed: Int): Rgba = Rgba(
            red = ((packed shr 16) and BYTE) / FULL,
            green = ((packed shr 8) and BYTE) / FULL,
            blue = (packed and BYTE) / FULL,
            alpha = ((packed shr 24) and BYTE) / FULL,
        )

        private const val BYTE = 0xFF

        /**
         * Alpha is optional and defaults to opaque, so a fully-lit colour is written as three numbers.
         */
        val CODEC: Codec<Rgba> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("red").forGetter(Rgba::red),
                Codec.FLOAT.fieldOf("green").forGetter(Rgba::green),
                Codec.FLOAT.fieldOf("blue").forGetter(Rgba::blue),
                Codec.FLOAT.optionalFieldOf("alpha", 1.0f).forGetter(Rgba::alpha),
            ).apply(instance, ::Rgba)
        }
    }
}
