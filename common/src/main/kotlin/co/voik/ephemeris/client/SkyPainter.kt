package co.voik.ephemeris.client

import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.Facing
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.VanillasBody
import co.voik.ephemeris.sky.SkySpec
import net.minecraft.client.Minecraft
import net.minecraft.world.level.MoonPhase
import org.joml.Quaternionf

/**
 * The batteries: whatever suns, moons and stars a level's [SkySpec] asks for, drawn.
 *
 * **This is the vocabulary tier, and it is a renderer registered through `LevelRendering` like any other.**
 * Nothing it does is closed to a caller who would rather draw their own — the convenient road and the
 * escape hatch are the same road, which is the property the whole appearance stack is built to keep.
 *
 * It decides which bodies go where and how bright and says so to a [SkyCanvas], knowing nothing about how a
 * frame is drawn. That is what keeps the whole of Blaze3D inside [Blaze3dSkyCanvas], and what would let a
 * second canvas exist without this file changing.
 *
 * The sky disc and the dark disc are left to vanilla — those are colour, and colour is already data an
 * environment layer can set. The **horizon glow is not**: see [HorizonPainter] for why vanilla's cannot
 * serve a sky with suns of its own.
 */
object SkyPainter {

    /** Below this the stars are too faint to be worth the draw. */
    private const val STARS_WORTH_DRAWING = 0.01f

    private const val FULL_TURN_RADIANS = (2.0 * Math.PI).toFloat()

    /** Vanilla reaches the axis it swings its sky about with this turn about the vertical. */
    private const val SKY_AXIS_DEGREES = -90.0f

    /**
     * Draws the level's bodies and stars, or returns **false** having drawn nothing.
     *
     * False means "vanilla should draw this one", and it is the ordinary answer in three cases: nothing has
     * said what this level looks like, the server has not told us yet, or what it told us is a sky vanilla
     * can already draw. Falling through beats imitating in all three — an ordinary sky then runs vanilla's
     * own code rather than a copy of it.
     *
     * [sunAngle], [moonAngle] and [starAngle] are vanilla's, in radians, and are used verbatim for bodies on
     * vanilla's own path. Everything else turns on the level's own clock.
     */
    fun draw(
        canvas: SkyCanvas,
        sunAngle: Float,
        moonAngle: Float,
        starAngle: Float,
        moonPhase: MoonPhase,
        rainBrightness: Float,
        starBrightness: Float,
    ): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val spec = LevelLooks.of(level.dimension())?.sky ?: return false
        if (spec.isOrdinary) return false

        val clockTime = level.defaultClockTime
        drawBodies(canvas, spec, clockTime, sunAngle, moonAngle, moonPhase, rainBrightness)

        val stars = spec.stars
        // A reveal dims by where the viewer is, on top of vanilla's night curve.
        val revealed = stars.reveal?.visibilityAt(eyeHeight()) ?: 1.0f
        val visibility = starBrightness * revealed
        if (stars.count > 0 && visibility > STARS_WORTH_DRAWING) {
            canvas.drawStarfield(stars.seed, stars.count, aroundVanillasAxis(starAngle), visibility, clockTime)
        }
        return true
    }

    /**
     * Where the viewer's eye is, for a [co.voik.ephemeris.sky.StarReveal] to read.
     *
     * The *camera*, not the player: in third person or spectator the sky should answer to where it is
     * being looked at from, and that is also the only position available while no player is embodied.
     */
    private fun eyeHeight(): Double = Minecraft.getInstance().gameRenderer.mainCamera.position().y

    /**
     * Every sun and moon the level has, **farthest first**, which is what lets a moon cover a sun behind it:
     * moons orbit inside every sun and do not draw additively. Ordering is the only tool available, the sky
     * pass writing no depth.
     */
    private fun drawBodies(
        canvas: SkyCanvas,
        spec: SkySpec,
        clockTime: Long,
        sunAngle: Float,
        moonAngle: Float,
        moonPhase: MoonPhase,
        rainBrightness: Float,
    ) {
        // Sorted by where each body is *now*: a path that swells and shrinks changes which body is in
        // front, and the sky pass writes no depth, so order is the only thing that decides.
        for (body in spec.bodies.sortedByDescending { it.path.distanceAt(clockTime) }) {
            val sprite = body.appearance as? Appearance.Sprite ?: continue
            val shape = sprite.shapes[shapeIndexOf(body, sprite, clockTime, moonPhase)]
            // A body that waxes and wanes is lit rather than luminous, and so covers rather than glows.
            val luminous = body.phase == null
            val tint = if (luminous) sprite.tint.dimmed(LUMINOUS_ADDS) else sprite.tint
            canvas.drawBody(
                shape = shape,
                orientation = facingOf(body, clockTime, sunAngle, moonAngle),
                distance = body.path.distanceAt(clockTime),
                angularSize = sprite.angularSize,
                tint = tint.copy(alpha = tint.alpha * rainBrightness),
                emitsOwnLight = luminous,
            )
        }
    }

    /**
     * How much of an authored tint a **luminous** body actually adds to the sky (Jonah, 2026-08-09, walked:
     * "the sun is still coming out almost white rather than red giant red").
     *
     * **A luminous body is blended additively** — `RenderPipelines.CELESTIAL` carries
     * `BlendFunction.OVERLAY`, which is `(SRC_ALPHA, ONE)`, so what is drawn is *summed* onto the sky rather
     * than covering it. A saturated tint at full brightness therefore drives its strongest channel to one
     * while the sky's other two are already high, and the middle of the disc comes out white with the colour
     * surviving only at the rim.
     *
     * Dimming what is added is the lever: the sun contributes less, so its dominant channel saturates over
     * a smaller area and the hue holds across more of the disc.
     *
     * **Know its ceiling, because it has a hard one.** Addition cannot take the sky's green and blue *away*,
     * so against a bright daytime sky no amount of dimming yields a deep red — the best it can do is stop
     * the core clipping. A red giant reads properly against the dim sky such a star would actually give, and
     * a level that wants one should ask for both. If a body must be dark against a bright sky, the
     * answer is not here but the occluding pipeline (`Blaze3dSkyCanvas.OCCLUDING_BODY_PIPELINE`), which
     * covers instead of adding and costs the body its glow.
     */
    private const val LUMINOUS_ADDS = 0.55f

    /**
     * How far around its circle a body is, in `0.0..1.0`.
     *
     * **A body on vanilla's own path takes vanilla's own angle.** That is exact where reconstructing the
     * day curve would only be close, and it survives vanilla changing that curve — which it has, the sun's
     * schedule now being a timeline rather than a formula. Everything else turns on the level's clock.
     */
    /** Where the body is, turned the way the body asks to be turned. */
    private fun facingOf(
        body: CelestialBody,
        clockTime: Long,
        sunAngle: Float,
        moonAngle: Float,
    ): Quaternionf {
        val alongPath = orientationOf(body, clockTime, sunAngle, moonAngle)
        return when (body.facing) {
            Facing.ALONG_PATH -> alongPath
            Facing.LIKE_VANILLA -> Facing.upright(alongPath)
        }
    }

    private fun orientationOf(
        body: CelestialBody,
        clockTime: Long,
        sunAngle: Float,
        moonAngle: Float,
    ): Quaternionf {
        val path = body.path
        val vanillasAngle = when (path.vanillas) {
            VanillasBody.SUN -> sunAngle
            VanillasBody.MOON -> moonAngle
            null -> return path.orientationAt(clockTime)
        }
        // Only an Orbit ever answers `vanillas`, and only its progress form can take vanilla's own angle.
        val circle = path as? Orbit ?: return path.orientationAt(clockTime)
        return circle.orientationAtProgress(vanillasAngle / FULL_TURN_RADIANS)
    }

    /**
     * Which of the body's shapes it is showing. A body with one shape never changes; vanilla's own moon
     * takes vanilla's phase, for the same reason its path does.
     */
    private fun shapeIndexOf(
        body: CelestialBody,
        sprite: Appearance.Sprite,
        clockTime: Long,
        moonPhase: MoonPhase,
    ): Int {
        val step = when {
            body.path == Orbit.VANILLA_MOON -> moonPhase.index()
            else -> body.phase?.stepAt(clockTime) ?: 0
        }
        return step.coerceIn(0, sprite.shapes.size - 1)
    }

    /**
     * Vanilla turns its sky about the world's ±X axis, reaching it with a quarter turn about the vertical
     * first. An [Orbit] carries that turn in its own ascending node, so this is only for the starfield,
     * which has no orbit.
     */
    private fun aroundVanillasAxis(angle: Float): Quaternionf =
        Quaternionf().rotateY(Math.toRadians(SKY_AXIS_DEGREES.toDouble()).toFloat()).rotateX(angle)
}
