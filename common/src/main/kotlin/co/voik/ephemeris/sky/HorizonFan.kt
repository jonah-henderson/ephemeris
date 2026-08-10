package co.voik.ephemeris.sky

import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Where vanilla's sunrise fan points, and how to aim it.
 *
 * **Its own file because the aiming is arithmetic and the drawing is not.** The fan is built and drawn deep
 * inside Blaze3D, where nothing can be checked; the one number that decides *which way it faces* is a turn
 * about Z, and getting it wrong puts every sunset somewhere it is not. So the turn lives here, with a check
 * that applies vanilla's own transform to vanilla's own centre vertex and reads the bearing back.
 *
 * The correction is a half turn, which is worth stating rather than hiding in a constant: the fan is stood
 * upright out of the ground by a quarter turn about X, and that carries its bright centre to the *far* side.
 * Aiming it by the bearing directly puts every glow exactly opposite the body that cast it — which reads as
 * "roughly right" for a body near the meridian and is wrong by a hundred and eighty degrees always.
 */
object HorizonFan {

    /** The turn about Z that puts the fan's bright centre at [bearingDegrees]. */
    fun turnFor(bearingDegrees: Float): Float = bearingDegrees - HALF_TURN

    /**
     * Where a fan turned by [turnDegrees] actually lands, by doing to a point what the canvas does to the
     * mesh. The inverse of [turnFor], and the only reason either can be checked without a screen.
     */
    fun landsAt(turnDegrees: Float): Float {
        val centre = Vector3f(0.0f, CENTRE_HEIGHT, 0.0f)
        Quaternionf()
            .rotateX(Math.toRadians(STAND_IT_UP.toDouble()).toFloat())
            .rotateZ(Math.toRadians(turnDegrees.toDouble()).toFloat())
            .transform(centre)
        return CelestialPath.bearingOf(centre)
    }

    /** Vanilla stands its fan up with a quarter turn about X before aiming it. */
    const val STAND_IT_UP = 90.0f

    private const val CENTRE_HEIGHT = 100.0f
    private const val HALF_TURN = 180.0f
}
