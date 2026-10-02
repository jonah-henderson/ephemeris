package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Streamers round a body — long, faint rays from its rim that slowly turn — and a **shimmer**, a slow pulse
 * in the strength of the rays and of the body's own glow.
 *
 * [colour] is premultiplied, as a [Palette]'s entries are: its red, green and blue are the light a ray gives
 * at its root and its alpha how much of the sky there it hides, so a corona may brighten the sky or darken
 * it. Every ray fades to nothing at its tip.
 */
data class Corona(
    /** How many streamers stand round the body. */
    val rays: Int,
    /** How far the longest reaches, in multiples of the body's own size. */
    val reach: Float,
    val colour: Rgba,
    /** How many times the streamers go round in a day. */
    val turnsPerDay: Float,
    /** How deep the pulse is, `0..1`: none at nought, fading to nothing and back at one. */
    val shimmer: Float,
) {
    /** One streamer at one moment: its bearing round the body in turns, its length and its strength. */
    data class Ray(val turn: Float, val length: Float, val strength: Float)

    /** How strong the corona and the body's glow are at [timeTicks], `1 - shimmer..1`. */
    fun strengthAt(timeTicks: Long): Float {
        val wave = (sin(timeTicks * TAU / PULSE_TICKS) + 1.0) / 2.0
        return (1.0 - shimmer * wave).toFloat()
    }

    /**
     * Every streamer at [timeTicks]. Each has a length of its own, fixed by its place in the ring so a
     * corona keeps its shape, and flickers on a phase of its own over the corona's pulse.
     */
    fun raysAt(timeTicks: Long): List<Ray> {
        val turned = (timeTicks.toDouble() / A_DAY * turnsPerDay).mod(1.0)
        val overall = strengthAt(timeTicks)
        return (0..<rays).map { index ->
            val own = ownShareOf(index)
            val flicker = (sin(timeTicks * TAU / FLICKER_TICKS + own * TAU) + 1.0) / 2.0
            Ray(
                turn = ((index.toDouble() / rays + turned) % 1.0).toFloat(),
                length = reach * (SHORTEST_SHARE + (1.0f - SHORTEST_SHARE) * own),
                strength = (overall * (1.0 - shimmer * FLICKER_DEPTH * flicker)).toFloat(),
            )
        }
    }

    /** A fixed fraction for the [index]th ray, scattered so neighbouring rays differ. */
    private fun ownShareOf(index: Int): Float = ((index * SCATTER) % 1.0).toFloat()

    companion object {
        private const val TAU = 2.0 * PI
        private const val A_DAY = 24_000.0

        /** A breath of the whole corona: half a minute. */
        private const val PULSE_TICKS = 600.0

        /** And each ray's own flicker, quicker. */
        private const val FLICKER_TICKS = 140.0

        /** How much of the shimmer a ray's own flicker takes, over the pulse they share. */
        private const val FLICKER_DEPTH = 0.5

        /** The shortest ray, as a share of the longest. */
        private const val SHORTEST_SHARE = 0.45f

        /** The golden ratio's fraction, which scatters an index round a ring without any two coming close. */
        private const val SCATTER = 0.6180339887

        val CODEC: Codec<Corona> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("rays").forGetter(Corona::rays),
                Codec.FLOAT.fieldOf("reach").forGetter(Corona::reach),
                Rgba.CODEC.fieldOf("colour").forGetter(Corona::colour),
                Codec.FLOAT.optionalFieldOf("turns_per_day", 0.0f).forGetter(Corona::turnsPerDay),
                Codec.FLOAT.optionalFieldOf("shimmer", 0.0f).forGetter(Corona::shimmer),
            ).apply(instance, ::Corona)
        }
    }
}
