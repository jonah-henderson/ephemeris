package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.CloudDeck
import co.voik.ephemeris.sky.Palette
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
     * **The sky's own colour: everything else here is drawn onto this.**
     *
     * It is what a level sees when it looks up at nothing, and until a writer says otherwise it is exactly
     * what vanilla would have drawn — which is why the shape is vanilla's numbers rather than ours.
     *
     * **A dome in name only.** Vanilla draws a flat disc, not a hemisphere: 512 blocks across at `y = +16`,
     * wider than the far plane and untextured, so a flat disc overhead reads as a whole sky. Being only
     * nine segments round, the horizon is a faint nonagon anybody who goes looking can find.
     *
     * [tint] is the sky's colour, which vanilla takes from the level's `sky_color` attribute; the fade
     * toward the horizon is the **fog**, not the disc, so whatever draws this must have uploaded the
     * frame's fog or the sky comes out flat.
     *
     * Drawn first and writing no depth, so every body, star and cloud lands on top of it whatever order
     * they are asked for in. [drawUnderside] is the other end of that sandwich.
     */
    fun drawDome(tint: Rgba)

    /**
     * The disc **below** the world, drawn last so that it hides what has set.
     *
     * The sibling of [drawDome] at `y = -16`, lifted 12 back up, and its own mirror: the rim is reversed in
     * x so it faces down. Vanilla draws it black, and only where the camera is high enough to see past the
     * world's edge.
     *
     * **Last, and that is the whole point of it being its own verb.** The sky pass writes no depth, so what
     * is drawn later covers what came before — which is how a sun that has gone down stops being visible
     * through the ground. Draw it with the dome and every body below the horizon shows through.
     *
     * Its colour is ours to play with where vanilla's is fixed: an Age standing on nothing may want to say
     * so.
     */
    fun drawUnderside(tint: Rgba)

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
     *
     * A [palette] draws the sprite's colours as its own, each covering or adding as it says, in place of
     * [tint]'s colour and of [emitsOwnLight]; [tint]'s alpha still fades it.
     */
    fun drawBody(
        shape: Identifier,
        orientation: Quaternionf,
        distance: Float,
        angularSize: Float,
        tint: Rgba,
        veil: Rgba,
        emitsOwnLight: Boolean,
        palette: Palette?,
    )

    /**
     * A plain quad of light — **no sprite, no texture, nothing but a colour**.
     *
     * The untextured sibling of [drawBody], and there for the case a body's sprite is working against you:
     * a borrowed sun carries its own yellow into whatever it is tinted, and its edges carry the sprite's
     * own alpha, so something meant to read as a bare point of light comes out as a small pale sun. This
     * adds itself the way a star does — `RenderPipelines.STARS` is exactly that shader — so it is
     * unambiguously light rather than a thing hanging in the sky.
     *
     * Positioned like a body: [orientation] is a rotation applied to a quad at `(0, distance, 0)`, and
     * [angularSize] is its half-extent in the same units, vanilla's sun being 30 at a distance of 100.
     */
    fun drawGlow(orientation: Quaternionf, distance: Float, angularSize: Float, tint: Rgba)

    /**
     * Many of those in **one** submission.
     *
     * [drawGlow] costs a render pass a glow, which is fine for the handful a sky usually hangs and is not
     * fine for a shower of a hundred — and a caller that caps the count to afford it draws the wrong ones:
     * whichever it leaves out appear from nowhere, already grown, the moment a slot frees.
     */
    fun drawGlows(glows: List<Glow>)

    /**
     * The level's stars, arranged by [seed] and turned up to [brightness]. Named by seed and count rather
     * than passed as points, the geometry never changing once built.
     *
     * [timeTicks] drives the twinkle and carries no partial tick — a fraction added to a counter that may
     * not have moved ratchets, and a twinkle is slow enough that whole ticks are smooth (see `Orbit`).
     */
    fun drawStarfield(seed: Long, count: Int, orientation: Quaternionf, brightness: Float, timeTicks: Long)

    /**
     * Everything [drawCloudDeck] will need uploaded, **before the frame reaches a render pass**.
     *
     * Since 26.3 a deck is drawn inside the transparency pass, and inside a pass only pass commands are
     * allowed: a texture that has to be loaded, a buffer that has to be mapped, a uniform that has to be
     * written and a ring buffer that has to be rotated are all refused there. So they happen here, where
     * `CloudRenderer.prepare` runs and no pass is open — the same split, for the same reason, that vanilla
     * made for its own clouds.
     *
     * Given the same [eye] and [timeTicks] the draw will be given, so anything derived from them agrees.
     */
    fun readyCloudDeck(deck: CloudDeck, eye: Vec3, timeTicks: Double)

    /**
     * One overcast layer, as a slab centred on the viewer. [eye] places it and also anchors the roil, which
     * is read in world coordinates so the pattern stays put as the player moves through it.
     *
     * Draws only: whatever this needed uploading was uploaded by [readyCloudDeck], and a deck that was not
     * readied this frame is not drawn.
     */
    fun drawCloudDeck(deck: CloudDeck, eye: Vec3, timeTicks: Double)

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
    fun drawAurora(aurora: Aurora, strength: Float, timeTicks: Long)

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

/** One glow's worth of [SkyCanvas.drawGlow]'s arguments, so a great many can go in at once. */
data class Glow(
    val orientation: Quaternionf,
    val distance: Float,
    val angularSize: Float,
    val tint: Rgba,
)
