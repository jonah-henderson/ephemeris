package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.CloudDeck
import co.voik.ephemeris.sky.Rainbow
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Everything a sky can be drawn with — five verbs, so the painters can decide what a sky looks like
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
     *
     * [veil] is the light scattered *in front* of the body, laid over it and never through it — its colour
     * is the air's and its alpha is how much of it there is. A body that covers must cover whatever the air
     * is doing, so this is added rather than blended and is [Rgba.CLEAR] for a body seen through nothing.
     */
    fun drawBody(
        shape: Identifier,
        orientation: Quaternionf,
        distance: Float,
        angularSize: Float,
        tint: Rgba,
        veil: Rgba,
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

    /**
     * A curtain of light crossing the sky at [Aurora.bearingDegrees], burning [Aurora.colours] crown to hem.
     *
     * [strength] is the whole of how *present* it is this instant — the night it is having, how dark the sky
     * has gone, and whatever the caller decides about the ground below, already multiplied together. Nought
     * draws nothing. The split is deliberate: what an aurora *is* travels in the spec and what it is *doing*
     * is decided per frame, so this verb needs to know nothing about either.
     *
     * [timeTicks] drives the fold and carries no partial tick, as [drawStarfield]'s does not — the motion is
     * far slower than a frame and a fraction added to a counter that may not have moved ratchets.
     */
    fun drawAurora(aurora: Aurora, strength: Float, timeTicks: Float)

    /**
     * A bow of [Rainbow.radiusDegrees] standing opposite a light at [lightAltitudeDegrees] and
     * [lightBearingDegrees], at [strength] of its full presence.
     *
     * **The light rather than the bow is what is passed**, because a bow has no place of its own: it is a
     * circle about the point exactly opposite whatever is lighting it, and that is the only thing anybody
     * has to be told. Two suns up at once are two calls, and the sky gets two bows with nothing here
     * knowing there was more than one.
     *
     * How high the light stands is what makes the arc, not merely where it is drawn: the antisolar point is
     * as far below the horizon as the light is above it, so a climbing light sinks its own bow. A caller
     * that has already declined to draw one ([Rainbow.castAt]) never gets here.
     */
    fun drawRainbow(
        rainbow: Rainbow,
        lightAltitudeDegrees: Float,
        lightBearingDegrees: Float,
        strength: Float,
    )
}
