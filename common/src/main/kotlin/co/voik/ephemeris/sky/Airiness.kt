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
 * **It costs nothing to draw, because translucent blending already is this equation.** A disc drawn at alpha
 * `a` gives `body * a + sky * (1 - a)`, which is exactly light through a partly-scattering medium. So the
 * haze is not painted on top of the moon; it is the moon's own opacity, and the sky behind shows through it.
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
     * One is untouched. Below that the sky shows through, which both pales the body and — deliberately —
     * stops it hiding what is behind it: a moon you can see through is a moon a sun shines past, and that is
     * the right answer rather than a compromise.
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

    /** How much air remains straight up, against the horizon's lot. */
    private const val THINNEST_OVERHEAD = 0.25f

    /** However thick the air, something of a body survives — otherwise it simply vanishes at dusk. */
    private const val LEAST_SOLID = 0.15f

    private const val RIGHT_ANGLE = 90.0f
}
