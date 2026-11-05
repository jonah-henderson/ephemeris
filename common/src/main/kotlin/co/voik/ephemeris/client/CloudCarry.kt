package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelWind
import co.voik.ephemeris.sky.Wind
import net.minecraft.client.multiplayer.ClientLevel

/**
 * How far a level's wind has carried its clouds, in blocks along X and Z.
 *
 * **Summed a frame at a time, never worked out from the clock**: the bearing swings, and time multiplied by today's
 * bearing would throw the whole sheet across the sky each time it moved. So each frame adds the step it covered,
 * held to what a frame can honestly carry, as [SkyDrift] holds its drifts.
 */
object CloudCarry {

    /** Where the clouds have been carried to, wrapped where the picture repeats. */
    data class Carried(val x: Double, val z: Double)

    private val carries = HashMap<String, Carry>()

    /**
     * How far [level]'s clouds have come by [ticks] at [pace] times vanilla's speed, or null where its air is still
     * and the clouds go vanilla's way at vanilla's rate.
     */
    fun of(level: ClientLevel, ticks: Double, pace: Float): Carried? {
        val wind = LevelWind.of(level)
        if (wind.isStill) return null
        val where = level.dimension().identifier().toString()
        return carries.getOrPut(where) { Carry(ticks) }.carriedTo(ticks, wind, pace)
    }

    private class Carry(start: Double) {
        private var lastSeen = start
        private var x = 0.0
        private var z = 0.0

        fun carriedTo(ticks: Double, wind: Wind, pace: Float): Carried {
            val step = (ticks - lastSeen).coerceIn(0.0, MOST_A_FRAME_MAY_CARRY)
            lastSeen = ticks
            val blowing = wind.at(ticks)
            val blocks = step * VANILLA_BLOCKS_PER_TICK * pace * (1.0 + blowing.strength * HURRIED_AT_FULL_STRENGTH)
            x = (x + blowing.x * blocks).mod(WRAP)
            z = (z + blowing.z * blocks).mod(WRAP)
            return Carried(x, z)
        }
    }

    /** The most one frame may carry the clouds, in ticks — [SkyDrift]'s bound, for its reasons. */
    private const val MOST_A_FRAME_MAY_CARRY = 4.0

    /** Vanilla's clouds drift 0.6 blocks a second. */
    const val VANILLA_BLOCKS_PER_TICK = 0.6 / 20.0

    /** How many times faster than vanilla's the clouds go in the strongest wind, over the pace already asked. */
    private const val HURRIED_AT_FULL_STRENGTH = 4.0

    /** A whole number of vanilla's cells across its whole picture, so the wrap lands where the picture repeats. */
    const val WRAP = 256.0 * 12.0
}
