package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelWind
import co.voik.ephemeris.sky.Wind
import net.minecraft.client.multiplayer.ClientLevel
import kotlin.math.pow

/**
 * How far a level's wind has carried its clouds, in blocks along X and Z.
 *
 * **Summed a frame at a time, never worked out from the clock**: the bearing swings, and time multiplied by today's
 * bearing would throw the whole sheet across the sky each time it moved. So each frame adds the step it covered,
 * held to what a frame can honestly carry, as [SkyDrift] holds its drifts.
 *
 * **Begun where vanilla's sheet stands, and kept once begun.** The first wind a level feels starts the carry from
 * vanilla's own offset at that moment, and when the air goes still again the carry goes on at vanilla's pace and
 * along vanilla's way rather than handing back to vanilla's clock — either seam would throw the sheet sideways.
 */
object CloudCarry {

    /** Where the clouds have been carried to, wrapped where the picture repeats. */
    data class Carried(val x: Double, val z: Double)

    private val carries = HashMap<String, Carry>()

    /**
     * How far [level]'s clouds have come by [ticks] at [pace] times vanilla's speed, or null where no wind has ever
     * blown there and the clouds go vanilla's way at vanilla's rate. [vanillasDrift] is how far vanilla's own sheet
     * has drifted along X by now, in blocks, which a carry begins from.
     */
    fun of(level: ClientLevel, ticks: Double, pace: Float, vanillasDrift: Double): Carried? {
        val wind = LevelWind.of(level)
        val where = level.dimension().identifier().toString()
        val carry = carries[where] ?: if (wind.isStill) return null else Carry(ticks, vanillasDrift).also { carries[where] = it }
        return carry.carriedTo(ticks, wind, pace)
    }

    /** Lets every carry go, for a client leaving its world. */
    fun forget() {
        carries.clear()
    }

    /** Begun at [start], from vanilla's sheet [vanillasDrift] blocks along X, which reads its picture that far east. */
    private class Carry(start: Double, vanillasDrift: Double) {
        private var lastSeen = start
        private var x = (-vanillasDrift).mod(WRAP)
        private var z = 0.0

        fun carriedTo(ticks: Double, wind: Wind, pace: Float): Carried {
            val step = (ticks - lastSeen).coerceIn(0.0, MOST_A_FRAME_MAY_CARRY)
            lastSeen = ticks
            if (wind.isStill) {
                // Vanilla's way at vanilla's rate: its picture read further east each tick, so the sheet goes west.
                x = (x - step * VANILLA_BLOCKS_PER_TICK * pace).mod(WRAP)
                return Carried(x, z)
            }
            val blowing = wind.at(ticks)
            val blocks = step * VANILLA_BLOCKS_PER_TICK * pace * hurriedBy(blowing.strength)
            x = (x + blowing.x * blocks).mod(WRAP)
            z = (z + blowing.z * blocks).mod(WRAP)
            return Carried(x, z)
        }
    }

    /**
     * How many times faster than vanilla's the clouds go in a wind of [strength]: barely faster in a breeze, half again
     * in a written wind, nearly twice in an ordinary gale, and three times only at the strongest.
     */
    private fun hurriedBy(strength: Float): Double {
        val blowing = strength.toDouble().coerceIn(0.0, 1.0)
        return 1.0 + blowing.pow(HURRY_POWER) * HURRIED_AT_FULL_STRENGTH
    }

    /** How sharply the clouds' hurry rises with the wind: by its fourth power. */
    private const val HURRY_POWER = 4.0

    /** The most one frame may carry the clouds, in ticks — [SkyDrift]'s bound, for its reasons. */
    private const val MOST_A_FRAME_MAY_CARRY = 4.0

    /** Vanilla's clouds drift 0.6 blocks a second. */
    const val VANILLA_BLOCKS_PER_TICK = 0.6 / 20.0

    /** How much faster than vanilla's the clouds go in the strongest wind, on top of their own: three times as fast. */
    private const val HURRIED_AT_FULL_STRENGTH = 2.0

    /** A whole number of vanilla's cells across its whole picture, so the wrap lands where the picture repeats. */
    const val WRAP = 256.0 * 12.0
}
