package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Which way a level's air moves and how hard: a prevailing bearing, swinging up to [wobbleDegrees] either way of
 * [bearingDegrees] from one day to the next, at [strength] from still (0) to as hard as it blows (1).
 *
 * The bearing is the way the air moves **towards**, in degrees from east (+X) turning to south (+Z); vanilla's
 * clouds drift west, at 180. The swing is a pure function of [seed] and the time, so every client reads the same
 * wind without being told it again.
 */
data class Wind(
    val bearingDegrees: Float,
    val strength: Float,
    val wobbleDegrees: Float = DEFAULT_WOBBLE_DEGREES,
    val seed: Long = 0L,
) {
    val isStill: Boolean get() = strength <= 0.0f

    /** The wind at [gameTime]: which way it blows, as a unit vector, and how hard. */
    fun at(gameTime: Double): Blowing {
        if (isStill) return Blowing.STILL
        val radians = Math.toRadians(bearingDegrees + wobbleDegrees * swingAt(gameTime))
        return Blowing(cos(radians), sin(radians), strength)
    }

    /** How far the bearing has swung at [gameTime], from -1 to 1, eased from each day's swing into the next. */
    private fun swingAt(gameTime: Double): Double {
        val days = gameTime / TICKS_PER_DAY
        val day = floor(days).toLong()
        val intoTheDay = days - day
        val eased = intoTheDay * intoTheDay * (3.0 - 2.0 * intoTheDay)
        val today = swingOn(day)
        return today + (swingOn(day + 1) - today) * eased
    }

    private fun swingOn(day: Long): Double = Random(seed xor (day * DAY_SALT)).nextDouble(-1.0, 1.0)

    /** The wind at one moment: [x] and [z] a unit vector the way it blows, [strength] as hard. */
    data class Blowing(val x: Double, val z: Double, val strength: Float) {
        companion object {
            val STILL = Blowing(1.0, 0.0, 0.0f)
        }
    }

    companion object {
        /** No wind at all: what a level that says nothing has. */
        val STILL = Wind(bearingDegrees = 0.0f, strength = 0.0f, wobbleDegrees = 0.0f)

        const val DEFAULT_WOBBLE_DEGREES = 20.0f

        private const val TICKS_PER_DAY = 24000.0

        /** Spreads consecutive days across the seed's random streams. */
        private const val DAY_SALT = -7046029254386353131L

        val CODEC: Codec<Wind> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("bearing").forGetter(Wind::bearingDegrees),
                Codec.FLOAT.fieldOf("strength").forGetter(Wind::strength),
                Codec.FLOAT.optionalFieldOf("wobble", DEFAULT_WOBBLE_DEGREES).forGetter(Wind::wobbleDegrees),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(Wind::seed),
            ).apply(instance, ::Wind)
        }
    }
}
