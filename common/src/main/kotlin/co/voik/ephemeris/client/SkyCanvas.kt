package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.CloudDeck
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Everything a sky can be drawn with — four verbs, so the painters can decide what a sky looks like
 * without knowing how a frame is drawn and the whole of Blaze3D stays behind [Blaze3dSkyCanvas].
 *
 * Building a sun or a moon does not belong here: what the resolver emits stays declarative data, because
 * it travels over the network and persists in a recipe.
 */
interface SkyCanvas {

    /**
     * A band of light on the horizon at [bearingDegrees], clockwise from north — one sunrise or one sunset.
     *
     * **Placed by a real bearing, where vanilla's is placed by a coin toss.** Vanilla only ever needs east
     * or west because its sun passes through the zenith and so is only ever at one or the other; a body on
     * any other path can be anywhere on the compass, and this is what lets it be drawn there.
     *
     * [tint] carries its own alpha, and that alpha is how strong the moment is. Several calls in one frame
     * simply add, which is what makes two suns setting in different quarters read as two events.
     */
    fun drawHorizonGlow(bearingDegrees: Float, tint: Rgba)

    /**
     * One sun or moon, as a quad facing the viewer. [orientation] carries the whole of where it is, being
     * a rotation applied to a body sitting at `(0, distance, 0)`; vanilla's sun is [angularSize] 30 at
     * [distance] 100.
     *
     * A body that [emitsOwnLight] adds itself to the sky and so reads as a light source; one that does not
     * covers what is behind it instead. Bodies are drawn farthest first, so the second kind occludes.
     */
    fun drawBody(
        shape: Identifier,
        orientation: Quaternionf,
        distance: Float,
        angularSize: Float,
        tint: Rgba,
        emitsOwnLight: Boolean,
    )

    /**
     * The level's stars, arranged by [seed] and turned up to [brightness]. Named by seed and count rather
     * than passed as points, the geometry never changing once built.
     *
     * [timeTicks] drives the twinkle and carries no partial tick — a fraction added to a counter that may
     * not have moved ratchets, and a twinkle is slow enough that whole ticks are smooth (see `Orbit`).
     */
    fun drawStarfield(seed: Long, count: Int, orientation: Quaternionf, brightness: Float, timeTicks: Long)

    /**
     * One overcast layer, as a slab centred on the viewer. [eye] places it and also anchors the roil, which
     * is read in world coordinates so the pattern stays put as the player moves through it.
     */
    fun drawCloudDeck(deck: CloudDeck, eye: Vec3, timeTicks: Float)
}
