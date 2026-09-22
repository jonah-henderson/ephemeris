package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.HorizonCrossing
import co.voik.ephemeris.sky.HorizonGlow
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.PhaseCycle
import co.voik.ephemeris.sky.SkyRules
import co.voik.ephemeris.sky.SkySpec
import co.voik.ephemeris.sky.StarField
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Which suns paint the horizon, where, and how strongly.
 *
 * Everything but the draw call itself is checkable, and this checks it through a canvas that records rather
 * than draws — so the rules about placement and strength are held without a game running, and only the
 * pixels are left needing eyes.
 */
class HorizonPainterCheck : FunSpec({

    /** One glow that was asked for. */
    data class Painted(val bearingDegrees: Float, val tint: Rgba)

    class RecordingCanvas : SkyCanvas {
        val glows = mutableListOf<Painted>()

        override fun drawDome(tint: Rgba) = Unit

        override fun drawUnderside(tint: Rgba) = Unit

        override fun drawHorizonGlow(bearingDegrees: Float, tint: Rgba) {
            glows += Painted(bearingDegrees, tint)
        }

        override fun drawGlow(
            orientation: Quaternionf,
            distance: Float,
            angularSize: Float,
            tint: Rgba,
        ) = Unit

        override fun drawGlows(glows: List<Glow>) = Unit

        override fun drawBody(
            shape: Identifier,
            orientation: Quaternionf,
            distance: Float,
            angularSize: Float,
            tint: Rgba,
            veil: Rgba,
            emitsOwnLight: Boolean,
        ) = Unit

        override fun drawStarfield(
            seed: Long,
            count: Int,
            orientation: Quaternionf,
            brightness: Float,
            timeTicks: Long,
        ) = Unit

        override fun drawCloudDeck(deck: co.voik.ephemeris.sky.CloudDeck, eye: Vec3, timeTicks: Double) = Unit

        override fun drawAurora(aurora: co.voik.ephemeris.sky.Aurora, strength: Float, timeTicks: Long) = Unit

        override fun drawRainbow(
            rainbow: co.voik.ephemeris.sky.Rainbow,
            lightAltitudeDegrees: Float,
            lightBearingDegrees: Float,
            strength: Float,
        ) = Unit
    }

    fun sun(orbit: Orbit, tint: Rgba = Rgba.WHITE) =
        CelestialBody(orbit, Appearance.Sprite(tint, 30.0f, Appearance.SUN_SHAPES))

    fun moon(orbit: Orbit) =
        CelestialBody(orbit, Appearance.Sprite(Rgba.WHITE, 20.0f, Appearance.MOON_SHAPES), PhaseCycle(24000, 0))

    fun lookOf(glow: HorizonGlow, vararg bodies: CelestialBody) = LevelLook(
        SkySpec(bodies.toList(), StarField(1500, 0L)),
        rules = SkyRules(glow = glow),
    )

    /** A tilted sun, so the sky is not one vanilla could draw and the painter engages at all. */
    val tilted = Orbit.VANILLA_SUN.copy(inclinationDegrees = 35.0f)
    val sunsetOf = { orbit: Orbit -> HorizonCrossing.next(orbit, 6000L)?.dayTime ?: error("never sets") }

    test("an ordinary sky is left to vanilla") {
        val canvas = RecordingCanvas()
        val ordinary = LevelLook(SkySpec.VANILLA)
        check(!HorizonPainter.paint(canvas, ordinary, 6000L)) {
            "The painter claimed an ordinary sky, so vanilla's own keyframed glow would stop running"
        }
        check(canvas.glows.isEmpty()) { "It also drew ${canvas.glows.size} glow(s) while declining" }
    }

    test("a sky with nothing to light a horizon has no sunrise at all") {
        // **Claiming the seam is not enough**, which is the trap this closes. With no suns the painter
        // draws no band and returns true, so vanilla's own glow never runs — but the level still carries
        // `SUNRISE_SUNSET_COLOR`, and `AtmosphericFogEnvironment` reads that attribute straight, washing
        // the fog warm on one horizon. The Spire has never had a sun and was getting a sunset (Jonah,
        // 2026-08-27, walked).
        val sunless = lookOf(HorizonGlow.BLENDED, moon(Orbit.VANILLA_MOON))
        check(!HorizonPainter.everGlows(sunless)) { "A sky with no suns was left vanilla's sunrise colour" }

        val silenced = lookOf(HorizonGlow.NONE, sun(tilted))
        check(!HorizonPainter.everGlows(silenced)) { "A sky that asked for no glow was left vanilla's" }

        check(HorizonPainter.everGlows(lookOf(HorizonGlow.BLENDED, sun(tilted)))) {
            "A sky with a sun in it lost the warm fog under its own band"
        }
        // Vanilla draws an ordinary sky's glow itself, and its own is the right one.
        check(HorizonPainter.everGlows(LevelLook(SkySpec.VANILLA))) { "An ordinary sky lost its sunrise" }
    }

    test("a sun at the horizon paints, and one overhead does not") {
        val look = lookOf(HorizonGlow.BLENDED, sun(tilted))

        val setting = RecordingCanvas()
        HorizonPainter.paint(setting, look, sunsetOf(tilted))
        check(setting.glows.size == 1) { "A setting sun painted ${setting.glows.size} glows, not one" }
        check(setting.glows.single().tint.alpha > 0.9f) {
            "A sun exactly on the horizon painted at only ${setting.glows.single().tint.alpha} strength"
        }

        val overhead = RecordingCanvas()
        HorizonPainter.paint(overhead, look, 6000L)
        check(overhead.glows.isEmpty()) { "A sun near its highest still painted the horizon" }
    }

    test("a moon never paints a sunset") {
        val canvas = RecordingCanvas()
        val look = lookOf(HorizonGlow.BLENDED, sun(tilted.copy(liftDegrees = -80.0f)), moon(tilted))
        HorizonPainter.paint(canvas, look, sunsetOf(tilted))
        check(canvas.glows.isEmpty()) {
            "A moon on the horizon painted ${canvas.glows.size} glow(s) — only suns light the sky"
        }
    }

    test("two suns paint in two places, which is the whole point") {
        // Half a turn apart, so when one sets the other is elsewhere on the compass.
        val other = tilted.copy(ascendingNodeDegrees = 30.0f, phaseDegrees = 12.0f)
        val canvas = RecordingCanvas()
        HorizonPainter.paint(canvas, lookOf(HorizonGlow.ADDITIVE, sun(tilted), sun(other)), sunsetOf(tilted))

        if (canvas.glows.size == 2) {
            val apart = Math.abs(canvas.glows[0].bearingDegrees - canvas.glows[1].bearingDegrees)
            check(apart > 1.0f) {
                "Two suns painted at the same bearing ($apart° apart), so they would read as one glow"
            }
        }
    }

    test("blending shares the light out; adding does not") {
        // Both suns on the horizon together, so the two rules visibly differ.
        val together = tilted.copy(ascendingNodeDegrees = 40.0f)
        val at = sunsetOf(tilted)

        fun strengthUnder(glow: HorizonGlow): Float {
            val canvas = RecordingCanvas()
            HorizonPainter.paint(canvas, lookOf(glow, sun(tilted), sun(together)), at)
            return canvas.glows.sumOf { it.tint.alpha.toDouble() }.toFloat()
        }

        val blended = strengthUnder(HorizonGlow.BLENDED)
        check(blended <= 1.001f) {
            "Blended glows summed to $blended, but sharing the light out should never exceed one whole sky"
        }
        check(strengthUnder(HorizonGlow.ADDITIVE) >= blended) {
            "Adding gave less light than blending, which inverts what the two words mean"
        }
    }

    test("nearest paints exactly one, and it is the nearest") {
        val higher = tilted.copy(phaseDegrees = 25.0f)
        val canvas = RecordingCanvas()
        HorizonPainter.paint(canvas, lookOf(HorizonGlow.NEAREST, sun(tilted), sun(higher)), sunsetOf(tilted))
        check(canvas.glows.size <= 1) { "NEAREST painted ${canvas.glows.size} glows" }
    }

    test("none paints nothing, but still claims the horizon") {
        val canvas = RecordingCanvas()
        // Claiming matters: declining would let vanilla's own glow run, which is not "no glow" at all.
        check(HorizonPainter.paint(canvas, lookOf(HorizonGlow.NONE, sun(tilted)), sunsetOf(tilted))) {
            "HorizonGlow.NONE declined the horizon, so vanilla would paint its own glow over an airless sky"
        }
        check(canvas.glows.isEmpty()) { "HorizonGlow.NONE drew ${canvas.glows.size} glow(s)" }
    }

    test("a sun's own colour survives into its light") {
        val canvas = RecordingCanvas()
        val blue = Rgba(0.3f, 0.5f, 1.0f)
        HorizonPainter.paint(canvas, lookOf(HorizonGlow.BLENDED, sun(tilted, blue)), sunsetOf(tilted))

        val painted = canvas.glows.single().tint
        check(painted.blue > painted.green) {
            "A blue sun set at $painted, which is not recognisably its own colour — the warming has taken over"
        }
        check(painted.red > blue.red) {
            "A blue sun set at $painted with no warming at all, so it would read as airless"
        }
    }
})
