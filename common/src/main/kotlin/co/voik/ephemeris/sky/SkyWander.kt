package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A sky that wanders slowly from one colour to the next and round again, never settling.
 *
 * Each of [colours] is reached in turn, [ticksPerColour] apart on the level's game time, and the sky is drawn
 * [strength] of the way from its own colour towards it — a hue only: the sky keeps its own brightness, so a
 * wandering sky still darkens at night. The fog follows at a share of that, or the horizon would stay the
 * colour the sky was.
 */
data class SkyWander(val colours: List<Rgba>, val strength: Float, val ticksPerColour: Int) {

    /**
     * [sky] leant towards where the wander stands at [gameTime], by [share] of [strength] — the colour moved
     * and its brightness kept, so night still darkens it.
     */
    fun over(sky: Rgba, gameTime: Long, share: Float = WHOLLY): Rgba {
        val towards = colourAt(gameTime)
        val lit = if (towards.brightness <= DARK) towards else towards.dimmed(sky.brightness / towards.brightness)
        return sky.lerp(lit, strength * share)
    }

    /** Where the wander stands at [gameTime]: the colour to lean the sky towards, before [strength]. */
    fun colourAt(gameTime: Long): Rgba {
        if (colours.size == 1) return colours.first()
        val position = (gameTime.toDouble() / ticksPerColour.coerceAtLeast(1)) % colours.size
        val from = position.toInt()
        val between = position - from
        val eased = (between * between * (3.0 - 2.0 * between)).toFloat()
        return colours[from].lerp(colours[(from + 1) % colours.size], eased)
    }

    companion object {
        private const val WHOLLY = 1.0f
        private const val DARK = 0.001f

        val CODEC: Codec<SkyWander> = RecordCodecBuilder.create { instance ->
            instance.group(
                Rgba.CODEC.listOf().fieldOf("colours").forGetter(SkyWander::colours),
                Codec.FLOAT.fieldOf("strength").forGetter(SkyWander::strength),
                Codec.INT.fieldOf("ticks_per_colour").forGetter(SkyWander::ticksPerColour),
            ).apply(instance, ::SkyWander)
        }
    }
}
