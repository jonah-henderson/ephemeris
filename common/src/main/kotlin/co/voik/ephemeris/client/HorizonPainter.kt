package co.voik.ephemeris.client

import org.joml.Vector4fc
import org.joml.Vector4f
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.BodyReading
import co.voik.ephemeris.sky.HorizonGlow
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes

/**
 * Sunrises and sunsets, one per sun.
 *
 * **Vanilla's glow cannot serve a sky with suns of its own**, and neither half of it survives. Its colour
 * comes from `SUNRISE_SUNSET_COLOR`, which the overworld timeline keyframes against the world clock, so the
 * light happens at vanilla's hours whatever the level's suns are doing. Its *position* is
 * `sin(sunAngle) < 0 ? 180 : 0` — east or west, and nothing between.
 *
 * That coin toss is not sloppiness: vanilla's sun passes through the zenith, so it really is only ever due
 * east or due west, and two values are a complete description. Tip a path at all and it stops being one.
 *
 * So each sun near the horizon paints its own band, at its own bearing, in its own colour — and how those
 * combine is [HorizonGlow], per level.
 */
object HorizonPainter {

    /**
     * Paints the level's horizon, or returns **false** having painted nothing.
     *
     * False leaves vanilla's own keyframed glow running, which is right for a level nothing has described
     * and for one whose sky vanilla could already draw — better its own curve than an imitation.
     */
    fun draw(canvas: SkyCanvas, level: ClientLevel): Boolean {
        val look = LevelLooks.of(level.dimension()) ?: return false
        return paint(canvas, look, level.defaultClockTime)
    }

    /**
     * Whether this level can ever light a horizon at all.
     *
     * **Shared with [silenceVanillasGlow] so the two cannot answer differently about one sky.** [paint]
     * returning true having drawn nothing is exactly this case, and the level still has vanilla's own
     * `SUNRISE_SUNSET_COLOR` sitting in its attributes — which nothing here draws but
     * `AtmosphericFogEnvironment` does, washing the fog warm on one horizon. A sky with no suns in it was
     * therefore getting a sunset (Jonah, 2026-08-27, walked, on the Spire).
     *
     * An ordinary sky is left alone: vanilla draws that one and its own glow is the right one.
     */
    fun everGlows(look: LevelLook): Boolean {
        if (look.sky.isOrdinary) return true
        if (look.rules.glow == HorizonGlow.NONE) return false
        return look.sky.bodies.any { it.phase == null }
    }

    /**
     * Takes vanilla's sunrise colour away from a level that has no sunrise — registered as an environment
     * layer, because the fog reads that attribute directly and no renderer stands between them.
     *
     * Levels that *do* light a horizon keep it: our own bands are drawn on the sky disc and vanilla's warm
     * fog beneath them is the right companion, placed by the same sun.
     */
    fun silenceVanillasGlow(
        level: ClientLevel,
        layers: EnvironmentAttributeSystem.Builder,
    ): EnvironmentAttributeSystem.Builder {
        val look = LevelLooks.of(level.dimension()) ?: return layers
        if (everGlows(look)) return layers
        return layers.addConstantLayer(EnvironmentAttributes.SUNRISE_SUNSET_COLOR) { UNLIT_HORIZON }
    }

    /** Fully transparent, which is what `SkyRenderer` and the fog both read as "no sunrise here". */
    /** Nothing at all, which 26.3 says as a vector where 26.2 said it as a packed zero. */
    private val UNLIT_HORIZON: Vector4fc = Vector4f(0.0f, 0.0f, 0.0f, 0.0f)

    /**
     * The same, given the appearance and the hour outright — **so it can be checked without a game running**.
     *
     * Finding the level is the only part of this that needs a client, and it is one line; keeping it out of
     * here is what lets every rule about which sun paints how strongly be held by a check.
     */
    fun paint(canvas: SkyCanvas, look: LevelLook, dayTime: Long): Boolean {
        if (look.sky.isOrdinary) return false
        if (look.rules.glow == HorizonGlow.NONE) return true

        val lit = look.readAt(dayTime).suns.filter { it.horizonNearness > 0.0f }
        if (lit.isEmpty()) return true

        for ((sun, strength) in strengths(lit, look.rules.glow)) {
            if (strength <= 0.0f) continue
            canvas.drawHorizonGlow(sun.azimuthDegrees, colourOf(sun).copy(alpha = strength))
        }
        return true
    }

    /**
     * How strongly each sun paints, under the level's rule.
     *
     * Every mode keeps the glows **separate**, which is the point of the whole exercise: a single averaged
     * band could not be in two places at once, and two suns setting in different quarters is exactly the
     * sight this exists to show.
     */
    private fun strengths(lit: List<BodyReading>, glow: HorizonGlow): List<Pair<BodyReading, Float>> =
        when (glow) {
            // Honest, and it clips: two suns low together really is twice the light.
            HorizonGlow.ADDITIVE -> lit.map { it to it.horizonNearness }

            // Shared out, so several sunsets are as bright as one and stay their own colours. Only ever
            // divides — one sun alone keeps its full strength rather than being scaled up to fill the sky.
            HorizonGlow.BLENDED -> {
                val total = lit.sumOf { it.horizonNearness.toDouble() }.toFloat()
                val share = if (total > 1.0f) 1.0f / total else 1.0f
                lit.map { it to it.horizonNearness * share }
            }

            HorizonGlow.NEAREST -> {
                val strongest = lit.maxByOrNull { it.horizonNearness }
                lit.map { it to if (it === strongest) it.horizonNearness else 0.0f }
            }

            HorizonGlow.NONE -> emptyList()
        }

    /**
     * What colour a sun's light is.
     *
     * The body's own tint, so a red giant sets red and a blue-white star does not — which is the thing a
     * writer means by choosing a sun. Warmed toward the horizon regardless, because a low sun is reddened by
     * the air it is seen through whatever colour it started, and a sky where that never happens reads as
     * airless.
     */
    private fun colourOf(sun: BodyReading): Rgba {
        val tint = (sun.body.appearance as? Appearance.Sprite)?.tint ?: Rgba.WHITE
        return tint.lerp(HORIZON_WARMTH, WARMING)
    }

    /** The colour the air lends a low sun — vanilla's own sunset is close to this. */
    private val HORIZON_WARMTH = Rgba(1.0f, 0.45f, 0.15f)

    /** How far a sun's own colour is carried toward that. Half, so neither the star nor the air wins. */
    private const val WARMING = 0.5f
}
