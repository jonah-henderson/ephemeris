package co.voik.ephemeris.sky

import net.minecraft.util.Mth

/**
 * How much air a body is seen through — **why a daytime moon is pale and a midnight one is not**.
 *
 * A body is not washed out by being dimmed. It is washed out by *sky light scattered in front of it*: the
 * air between you and it glows, and the further you look through, the more of that glow is added and the
 * less of the body survives. That is why a moon overhead at midnight is crisp and white, one low over a
 * blue afternoon is a ghost, and one on an airless world is as hard-edged at noon as at midnight.
 *
 * **It is a sum of two lights, and not an opacity.** What reaches the eye is `body × solidity` plus the
 * air's own glow at `1 - solidity`. Translucent blending computes that same sum for free — and computes it
 * against *whatever happens to be behind*, which is why the moon was drawn at alpha `solidity` for a long
 * time and why a sun behind one shone straight through the rock (Jonah, 2026-08-27, walked). The two agree
 * exactly against plain sky and nowhere else, so the sum is now written out: the body dims and the air's
 * light is laid over it.
 *
 * Two things decide it, and both are what they are in the world:
 *
 * - **How lit the air is.** At night there is nothing to scatter and a body is solid.
 * - **How low the body is.** Looking at the horizon is looking along the air rather than through it, so a
 *   setting body is seen through far more of it than one overhead.
 */
object Airiness {

    /**
     * How solid a body at [altitudeDegrees] looks when the sky around it is [skyLit] bright, `0..1`.
     *
     * One is untouched. Below that this much of the body survives the air and the rest of what the eye
     * gets is the air's own glow — which pales the body without making it see-through. A moon is rock at
     * every hour of the day, and a sun behind one is behind it.
     */
    fun solidityAt(altitudeDegrees: Float, skyLit: Float, thickness: Float): Float {
        if (thickness <= 0.0f) return 1.0f
        val throughTheAir = airmassAt(altitudeDegrees)
        val washed = skyLit.coerceIn(0.0f, 1.0f) * throughTheAir * thickness
        return (1.0f - washed).coerceIn(LEAST_SOLID, 1.0f)
    }

    /**
     * How much air lies along the line of sight, `0..1` — **overhead is least, the horizon most**.
     *
     * The real relation runs as one over the sine of the altitude and climbs without limit at the horizon;
     * this is that shape flattened into a bounded one, because a body on the horizon should be pale rather
     * than gone. Below the horizon it stops changing: a body already set is not going to get any hazier.
     */
    fun airmassAt(altitudeDegrees: Float): Float {
        val above = altitudeDegrees.coerceIn(0.0f, RIGHT_ANGLE)
        val overhead = Math.sin(Math.toRadians(above.toDouble())).toFloat()
        return Mth.lerp(overhead, 1.0f, THINNEST_OVERHEAD)
    }

    /**
     * How much air remains straight up, against the horizon's lot.
     *
     * **Not far off half, because the sky overhead is not clear** — a daytime moon at the zenith is washed
     * pale blue rather than hanging crisp against it, and a quarter left it reading as barely veiled at all
     * (Jonah, 2026-08-30, walked). The horizon still gets four times the airmass, which is what keeps a
     * setting moon the dramatic one; this is only the floor under it.
     */
    private const val THINNEST_OVERHEAD = 0.45f

    /** However thick the air, something of a body survives — otherwise it simply vanishes at dusk. */
    private const val LEAST_SOLID = 0.15f

    private const val RIGHT_ANGLE = 90.0f
}
