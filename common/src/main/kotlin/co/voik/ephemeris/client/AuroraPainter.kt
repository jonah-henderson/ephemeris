package co.voik.ephemeris.client

import co.voik.ephemeris.RuntimeLevelLog
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.AuroraGround
import co.voik.ephemeris.sky.LevelDaylight
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3

/**
 * The curtain a level was written with, drawn over whatever drew its sky — the painter for [Aurora] and the
 * third sibling of [SkyPainter] and [CloudPainter].
 *
 * **An overlay rather than a claim** ([LevelRendering.skyOverlay]): a level that hangs an aurora over an
 * otherwise ordinary sky keeps vanilla's own sun and moon and gains a curtain, where folding this into
 * [SkyPainter] would have made an aurora a reason to replace the lot.
 *
 * What is decided here is only *how present* the curtain is this instant. What it looks like is the spec's.
 */
object AuroraPainter {

    /**
     * Draws the level's aurora, if it has one and this is a night it comes.
     *
     * **Every path out of here says which one it took**, including the ones that decline. A first draft
     * logged only the decision to draw, which meant silence covered four different answers — no curtain
     * written, none sent, none tonight, and *this function never ran* — and the last of those is the one a
     * walk most needs to tell apart from the rest (Jonah, 2026-08-30: "that is a no line output"). Silence
     * now means one thing only, which is what makes it evidence.
     */
    fun draw(canvas: SkyCanvas, rainBrightness: Float, starBrightness: Float) {
        val level = Minecraft.getInstance().level
        if (level == null) return sayItIs("no level to draw in")
        val look = LevelLooks.of(level.dimension())
        if (look == null) return sayItIs("nothing has said what ${level.dimension().identifier()} looks like")
        val aurora = look.sky.aurora
        if (aurora == null) return sayItIs("the look for ${level.dimension().identifier()} carries no curtain")

        val strength = strengthOf(aurora, level, look, rainBrightness, starBrightness)
        if (strength <= WORTH_DRAWING) return sayItIs("a curtain is written but not showing, at %.3f".format(strength))
        sayItIs("a curtain is up, drawing at %.3f".format(strength))
        canvas.drawAurora(aurora, strength, level.defaultClockTime.toFloat())
    }

    /**
     * One line whenever the answer changes, and nothing while it stays the same.
     *
     * Rate-limited by the answer itself rather than by a clock: a night costs two lines however many frames
     * it lasts, and a *changed* answer is never swallowed by a timer.
     */
    private fun sayItIs(state: String) {
        if (state == said) return
        said = state
        RuntimeLevelLog.info("Aurora: $state")
    }

    /** What was last said, so only a change is worth saying. */
    private var said: String? = null

    /**
     * How present the curtain is right now, `0..1` — independent things multiplied together, each of which
     * can silence it on its own.
     *
     * **The night it is having** is the spec's own arithmetic and so is the same on every client, which is
     * the whole of why an aurora needs no server tick. **How dark the sky has gone** is read the way
     * [SkyPainter] reads it for the stars — off the level's own suns rather than off vanilla's clock — so an
     * Age with three suns, or one pinned at midnight, is answered correctly with nothing written for it.
     * **Weather** hides an aurora as it hides the stars. And **the ground** decides whether it may be seen
     * from where the viewer is standing at all ([SnowLine]).
     *
     * The ground is asked **last**, and only where a rule says to: it is the one factor that costs a walk
     * over the level, and on a night the curtain is not having, or at noon, there is nothing for it to
     * decide.
     */
    private fun strengthOf(
        aurora: Aurora,
        level: ClientLevel,
        look: LevelLook,
        rainBrightness: Float,
        starBrightness: Float,
    ): Float {
        val tonight = aurora.strengthOn(level.defaultClockTime / TICKS_PER_DAY)
        if (tonight <= NOTHING) return NOTHING
        // A level that pins its stars means it, and an aurora keeps the hours its stars keep.
        val nightliness = look.air.starBrightness
            ?: LevelDaylight.starlitnessFor(level)
            ?: (starBrightness / VANILLAS_BRIGHTEST_STARS)
        val clearSky = 1.0f - rainBrightness
        val inTheSky = tonight * nightliness * clearSky * aurora.glow
        if (inTheSky <= WORTH_DRAWING) return NOTHING
        return (inTheSky * groundUnder(aurora, level)).coerceIn(NOTHING, 1.0f)
    }

    /** How much of the ground around the viewer answers this curtain's rule, `1` where it asks for none. */
    private fun groundUnder(aurora: Aurora, level: ClientLevel): Float = when (aurora.ground) {
        AuroraGround.ANYWHERE -> 1.0f
        AuroraGround.WHERE_IT_SNOWS -> SnowLine.shareSeenFrom(level, eye())
    }

    /** Where the viewer is. The camera rather than the player, so a spectator sees what they are looking from. */
    private fun eye(): Vec3 = Minecraft.getInstance().gameRenderer.mainCamera.position()

    /** Below this there is nothing on the screen and the pass is not worth opening. */
    private const val WORTH_DRAWING = 0.01f

    /** How bright vanilla's own stars ever get — [SkyPainter]'s number, read here for the same reason. */
    private const val VANILLAS_BRIGHTEST_STARS = 0.5f

    private const val NOTHING = 0.0f

    private const val TICKS_PER_DAY = 24000L
}
