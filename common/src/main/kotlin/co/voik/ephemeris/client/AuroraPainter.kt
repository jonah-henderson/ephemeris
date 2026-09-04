package co.voik.ephemeris.client

import co.voik.ephemeris.RuntimeLevelLog
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.AuroraGround
import co.voik.ephemeris.sky.LevelDaylight
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.Camera
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
     * How many curtains this client will draw at once, however many a level asks for.
     *
     * **The library draws what it is told, so the number belongs to whoever is doing the telling.** If a
     * level asks for six curtains it is because a consumer decided six, and a consumer may have reasons for
     * that even on a machine that struggles — theirs to weigh, not ours to second-guess. What Ephemeris
     * owes them is the lever (Jonah, 2026-08-30).
     *
     * **A function rather than a number**, so a setting takes effect the moment it changes and neither side
     * needs an event to keep the two in step. Nought draws none at all, which is a real thing to want.
     */
    var mostCurtainsDrawn: () -> Int = { Aurora.MOST_CURTAINS }

    /**
     * Draws the level's aurora, if it has one and this is a night it comes.
     *
     * **Every path out of here says which one it took, and a declining path says which factor was at
     * nought.** Silence covering four answers was the first mistake; "not showing, at 0.000" covering four
     * *reasons* was the second, and it cost another walk (Jonah, 2026-08-30). A number that is nought says
     * only that something is, which is the one thing already known by the time anybody looks.
     */
    fun draw(canvas: SkyCanvas, level: ClientLevel, camera: Camera, rainBrightness: Float, starBrightness: Float) {
        if (level == null) return sayIt("nowhere", "no level to draw in")
        val where = level.dimension().identifier()
        val look = LevelLooks.of(level.dimension())
        if (look == null) return sayIt("untold", "nothing has said what $where looks like")
        val aurora = look.sky.aurora
        if (aurora == null) return sayIt("bare", "the look for $where carries no curtain")

        val tonight = aurora.strengthOn(level.defaultClockTime / TICKS_PER_DAY)
        if (tonight <= NOTHING) return sayIt("off", "a curtain is written, but tonight is not one of its nights")

        // A level that pins its stars means it, and an aurora keeps the hours its stars keep.
        val nightliness = look.air.starBrightness
            ?: LevelDaylight.starlitnessFor(level)
            ?: (starBrightness / VANILLAS_BRIGHTEST_STARS)
        if (nightliness <= WORTH_DRAWING) {
            return sayIt("light", "a curtain is up tonight, but the sky is only %.3f dark".format(nightliness))
        }

        // **Used as it comes, not inverted.** `rainBrightness` is how much of the sky the weather leaves —
        // 1 when it is clear — which is why `SkyPainter` multiplies a body's alpha by it.
        val clearSky = rainBrightness
        if (clearSky <= WORTH_DRAWING) return sayIt("weather", "a curtain is up tonight, but the weather has it")

        val ground = groundUnder(aurora, level, camera)
        if (ground <= WORTH_DRAWING) {
            return sayIt("warm", "a curtain is up tonight, but nothing within sight of you is cold enough")
        }

        val strength = (tonight * nightliness * clearSky * aurora.glow * ground).coerceIn(NOTHING, 1.0f)
        if (strength <= WORTH_DRAWING) return sayIt("faint", "a curtain is up but too faint to draw")

        val budget = mostCurtainsDrawn()
        if (budget <= NONE_DRAWN) return sayIt("budget", "a curtain is up, but this client is drawing none")
        val within = aurora.copy(curtains = aurora.curtains.coerceAtMost(budget))
        sayIt("up", "a curtain is up, drawing ${within.curtains} at %.3f".format(strength))
        canvas.drawAurora(within, strength, level.defaultClockTime.toFloat())
    }

    /** How much of the ground around the viewer answers this curtain's rule, `1` where it asks for none. */
    private fun groundUnder(aurora: Aurora, level: ClientLevel, camera: Camera): Float = when (aurora.ground) {
        AuroraGround.ANYWHERE -> 1.0f
        // The camera rather than the player: a spectator sees what they are looking from, and a level being
        // drawn off-screen is looked at from somewhere the player is not.
        AuroraGround.WHERE_IT_SNOWS -> SnowLine.shareSeenFrom(level, camera.position())
    }

    /**
     * One line whenever the *reason* changes, and nothing while it stays the same.
     *
     * Keyed on the reason rather than on the message, so a brightening curtain does not write a line a
     * frame while a changed answer is never swallowed.
     */
    private fun sayIt(reason: String, message: String) {
        if (reason == said) return
        said = reason
        RuntimeLevelLog.info("Aurora: $message")
    }

    /** Which reason was last given, so only a change is worth saying. */
    private var said: String? = null

    /** Below this there is nothing on the screen and the pass is not worth opening. */
    private const val WORTH_DRAWING = 0.01f

    /** How bright vanilla's own stars ever get — [SkyPainter]'s number, read here for the same reason. */
    private const val VANILLAS_BRIGHTEST_STARS = 0.5f

    private const val NOTHING = 0.0f

    private const val NONE_DRAWN = 0

    private const val TICKS_PER_DAY = 24000L
}
