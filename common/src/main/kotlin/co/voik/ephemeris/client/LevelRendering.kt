package co.voik.ephemeris.client

import co.voik.ephemeris.RuntimeLevelLog
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.level.MoonPhase
import net.minecraft.world.phys.Vec3

/**
 * How a level looks, for levels that should not look like the overworld.
 *
 * **This is the escape hatch, and everything else is built on it.** The vocabulary tier — suns, moons,
 * starfields, cloud decks — is a renderer registered here like any other, so nothing it can do is closed to
 * a caller who wants to draw their own, and the convenient road is the same road.
 *
 * **It exists because 26.1 closed every other one.** `DimensionSpecialEffects` is gone, Fabric API dropped
 * `DimensionRenderingRegistry`, and a dimension type still cannot carry this to the client — so a mod that
 * wants a sky of its own has to Mixin `SkyRenderer` itself. Every such mod writes the same two Mixins, and
 * two mods that do fight over the same seam.
 *
 * **A renderer decides for itself whether it applies**, rather than being registered against a level key.
 * That is deliberate: the interesting cases are dynamic — a level made at runtime, a look that changes when
 * its description is rewritten — and a registration keyed by dimension cannot express either. Returning
 * `false` means "not mine", and the next renderer is asked; if none claims it, vanilla draws its own.
 *
 * **Registration order is the order asked**, and the first claim wins.
 *
 * **A renderer should read its moment and never the game.** Every moment carries the level being drawn, the
 * target being drawn onto and the camera it is seen through, so a renderer that takes them from there works
 * unchanged when something renders a level off-screen — a portal, a mirror, a preview panel. Reaching for
 * `Minecraft.getInstance()` instead is the one mistake this seam cannot protect you from, and it fails
 * silently: the sky drawn is the player's, and only ever looks wrong to somebody else.
 */
object LevelRendering {

    private val skies = mutableListOf<LevelSkyRenderer>()
    private val clouds = mutableListOf<LevelCloudRenderer>()
    private val horizons = mutableListOf<LevelHorizonRenderer>()
    private val overlays = mutableListOf<LevelSkyOverlay>()
    private val environments = mutableListOf<LevelEnvironment>()

    /** Offer to draw suns, moons and stars. */
    fun sky(renderer: LevelSkyRenderer) {
        skies += renderer
    }

    /** Offer to draw the overcast. */
    fun clouds(renderer: LevelCloudRenderer) {
        clouds += renderer
    }

    /**
     * Offer to paint the light at the horizon — the sunrise and the sunset.
     *
     * A seam of its own because vanilla draws this separately from the bodies, and because neither half of
     * what vanilla does survives a sky with suns of its own: the colour is keyframed against the world clock,
     * and the position is a coin toss between east and west.
     */
    fun horizon(renderer: LevelHorizonRenderer) {
        horizons += renderer
    }

    /**
     * Offer to draw **over** whatever drew the sky — a curtain, a haze, an aurora.
     *
     * Unlike [sky] this one **does not claim**, in the way [environment] does not: every overlay registered
     * is drawn, and it is drawn whether this library's own renderer took the sky or vanilla drew its own.
     *
     * That is the whole reason it exists rather than being folded into [sky]. Something a level adds
     * *above* its sun and moon is not a reason to take its sun and moon away from it: a level whose sky is
     * one vanilla can already draw should go on getting vanilla's own code rather than an imitation of it,
     * and still get whatever it added. A claiming seam cannot express that.
     */
    fun skyOverlay(overlay: LevelSkyOverlay) {
        overlays += overlay
    }

    /**
     * Offer to bend a level's air — its fog, sky colour, light tint and how far you can see.
     *
     * Unlike the two above this one **does not claim**: every layer offered is applied, in order, over
     * whatever the last one left. Attributes compose by their nature and a first-claim rule would make two
     * mods that both tint the fog silently exclusive.
     */
    fun environment(layers: LevelEnvironment) {
        environments += layers
    }

    // The three below are what the Mixins call. Public because a Java Mixin cannot see a Kotlin
    // `internal` — the name is mangled — and not because a consumer has any business calling them.

    /** Asked by the sky Mixin. Registering a renderer is the way in; this is the way out. */
    fun drawSky(moment: SkyMoment): Boolean {
        watchTheClocks(moment)
        return skies.any { it.draw(moment) }
    }

    /**
     * What the sky is drawn from, checked for running backwards — see [SkyClockWatch].
     *
     * Read at the seams rather than inside a painter, so the numbers watched are the ones vanilla handed
     * over and not something a painter has already done arithmetic to.
     */
    private fun watchTheClocks(moment: SkyMoment) {
        SkyClockWatch.reading(moment.level, "defaultClock", moment.level.defaultClockTime.toDouble())
        SkyClockWatch.reading(moment.level, "starAngle", moment.starAngle.toDouble(), wrapsAt = A_WHOLE_TURN)
        SkyClockWatch.reading(moment.level, "sunAngle", moment.sunAngle.toDouble(), wrapsAt = A_WHOLE_TURN)
    }

    /**
     * The cloud seam's own two. `defaultClock` is deliberately **not** read again here: one clock wants one
     * reader, or its per-frame average comes out halved by being sampled twice a frame.
     */
    private fun watchTheClocks(moment: CloudMoment) {
        SkyClockWatch.reading(moment.level, "gameTime", moment.gameTime.toDouble())
        SkyClockWatch.reading(moment.level, "cloudTime", moment.time)
    }

    private const val A_WHOLE_TURN = 2.0 * Math.PI

    /**
     * Asked by the sky Mixin, from **both** of its injectors — once where a renderer claimed the sky, and
     * once at the tail where vanilla drew it. A cancelled head returns at the injection point, so the tail
     * runs exactly when the head did not cancel and each path draws these once.
     */
    fun drawSkyOverlays(moment: SkyMoment) {
        // **Once, and only to say the seam was reached.** An overlay that draws nothing is indistinguishable
        // from a seam that never runs, and the two want opposite investigations — so the seam says which it
        // is, exactly once, rather than leaving silence to mean both (Jonah, 2026-08-30, walked).
        if (!saidTheSeamRan) {
            saidTheSeamRan = true
            RuntimeLevelLog.info("Sky overlays reached, ${overlays.size} registered")
        }
        for (overlay in overlays) overlay.draw(moment)
    }

    private var saidTheSeamRan = false

    /** Asked by the cloud Mixin. */
    fun drawClouds(moment: CloudMoment): Boolean {
        watchTheClocks(moment)
        return clouds.any { it.draw(moment) }
    }

    /** Asked by the sunrise Mixin. */
    fun drawHorizon(moment: HorizonMoment): Boolean = horizons.any { it.draw(moment) }

    /** Asked by the client-level Mixin, once, as the level builds its attribute system. */
    fun paintEnvironment(
        level: ClientLevel,
        layers: EnvironmentAttributeSystem.Builder,
    ): EnvironmentAttributeSystem.Builder = environments.fold(layers) { built, each -> each.paint(level, built) }
}

/**
 * Everything vanilla knows at the instant it is about to draw a sky.
 *
 * [level] and [target] together are what let a renderer work for a level that is not the player's and a
 * frame that is not the window — a preview panel, a portal, a camera. Read them rather than reaching for
 * `Minecraft`, and a renderer costs nothing to reuse off-screen.
 */
class SkyMoment(
    val level: ClientLevel,
    /** Where this frame is being drawn. The window's own target in ordinary play. */
    val target: RenderTarget,
    /**
     * The camera this frame is seen through.
     *
     * Ask this for the eye position, the look direction, or an environment attribute where the eye is —
     * never `gameRenderer.mainCamera`, which is the *player's* however the frame came to be drawn.
     */
    val camera: Camera,
    /**
     * How far into the tick being drawn this frame is, `0..1`.
     *
     * Anything whose place is worked out from the level's clock wants this on top of it, or it moves in
     * whole-tick steps at twenty a second however many frames are drawn between them.
     */
    val partOfATickOn: Float,
    /** Vanilla's own angles, in radians, so a body on its path needs nothing reconstructed. */
    val sunAngle: Float,
    val moonAngle: Float,
    val starAngle: Float,
    val moonPhase: MoonPhase,
    /**
     * How much of the sky the weather is **leaving**, `0..1` — **1 in clear weather and 0 in a downpour**,
     * which is the opposite of what the name suggests.
     *
     * The name is vanilla's and so is the quantity: `SkyRenderer.renderSun` multiplies its sprite's alpha by
     * this, so a body *dims* as it grows. Said this plainly because the old wording here said the reverse
     * and a painter believed it — an aurora multiplied by `1 - this` and so could only ever appear in a
     * storm, which read as a renderer that never worked at all (Jonah, 2026-08-30, walked).
     */
    val rainBrightness: Float,
    /** How visible stars are at this hour, before anything of yours dims them further. */
    val starBrightness: Float,
)

/** Draws a level's suns, moons and stars. */
fun interface LevelSkyRenderer {
    /** **True** if this drew the sky; **false** to pass, leaving it to the next renderer or to vanilla. */
    fun draw(moment: SkyMoment): Boolean
}

/**
 * Draws whatever a level hangs above its sun, moon and stars.
 *
 * No return: an overlay adds to the sky rather than deciding it, so there is nothing for it to claim and
 * nothing for a caller to do about the answer.
 */
fun interface LevelSkyOverlay {
    fun draw(moment: SkyMoment)
}

/**
 * Everything vanilla knows at the instant it is about to paint the horizon.
 *
 * [vanillaColour] is what vanilla would have used, packed ARGB, straight off
 * `EnvironmentAttributes.SUNRISE_SUNSET_COLOR` — worth having even for a renderer that ignores it, because
 * its alpha is vanilla's own judgement of how strong the moment is.
 */
class HorizonMoment(
    val level: ClientLevel,
    /** Where this frame is being drawn. The window's own target in ordinary play. */
    val target: RenderTarget,
    /**
     * The camera this frame is seen through.
     *
     * Ask this for the eye position, the look direction, or an environment attribute where the eye is —
     * never `gameRenderer.mainCamera`, which is the *player's* however the frame came to be drawn.
     */
    val camera: Camera,
    /** Vanilla's own sun angle, in radians. */
    val sunAngle: Float,
    val vanillaColour: Int,
)

/** Paints a level's sunrises and sunsets. */
fun interface LevelHorizonRenderer {
    /** **True** if this painted the horizon; **false** to pass, leaving vanilla's own glow to run. */
    fun draw(moment: HorizonMoment): Boolean
}

/** Everything vanilla knows at the instant it is about to draw clouds. */
class CloudMoment(
    val level: ClientLevel,
    /**
     * Where the cloud pass is being drawn.
     *
     * Not always the same target as the rest of the frame: vanilla gives clouds one of their own when the
     * transparency chain is on.
     */
    val target: RenderTarget,
    /**
     * The camera this frame is seen through.
     *
     * Ask this for the eye position, the look direction, or an environment attribute where the eye is —
     * never `gameRenderer.mainCamera`, which is the *player's* however the frame came to be drawn.
     */
    val camera: Camera,
    val colour: Int,
    val status: net.minecraft.client.CloudStatus,
    val bottomY: Float,
    val range: Int,
    val cameraPosition: Vec3,
    /** The level's clock, in whole ticks. */
    val gameTime: Long,
    /**
     * How far into the tick being drawn this frame is, `0..1`, as [SkyMoment.partOfATickOn].
     *
     * Kept apart from [gameTime] rather than added into it, because the sum does not fit a `Float`: game
     * time passes 2^24 after a few weeks of play and a float has no room left for a fraction, so a single
     * `gameTime + partialTicks` quantises the drift long before that and then stops moving within a tick
     * at all. [time] does the addition in a `Double`, where it costs nothing.
     */
    val partOfATickOn: Float,
) {
    /** The clock with this frame's fraction on it — what anything drifting with time should read. */
    val time: Double get() = gameTime + partOfATickOn.toDouble()
}

/** Draws a level's overcast. */
fun interface LevelCloudRenderer {
    /** **True** if this drew the clouds; **false** to pass. */
    fun draw(moment: CloudMoment): Boolean
}

/**
 * Bends a level's air.
 *
 * The builder is vanilla's own, so anything it accepts works here — a constant layer for the whole level, a
 * layer that applies only in one biome, or a **positional** one asked per position, which is what a gradient
 * around something in the world needs.
 */
fun interface LevelEnvironment {
    fun paint(
        level: ClientLevel,
        layers: EnvironmentAttributeSystem.Builder,
    ): EnvironmentAttributeSystem.Builder
}
