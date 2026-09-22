package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3

/**
 * The overcast a level was written with, in place of vanilla's one drifting sheet — the painter for clouds
 * and the sibling of [SkyPainter].
 *
 * **A level wanting decks must not set a fully transparent `minecraft:visual/cloud_color`**: `LevelRenderer`
 * skips the cloud pass entirely on a zero alpha, taking ours with it. It also skips when the player has
 * clouds off, which is theirs to decide.
 */
object CloudPainter {

    /**
     * The uploads the draw below will need, done where no pass is open — see [SkyCanvas.readyCloudDeck].
     *
     * **It reads the same decks and the same drift as [draw]**, which is the whole of why it is here rather
     * than in the Mixin: a prepare that disagreed with its draw about either would upload one deck's
     * uniforms and bind them for another.
     */
    fun ready(canvas: SkyCanvas, level: ClientLevel, eye: Vec3, timeTicks: Double) {
        val spec = LevelLooks.of(level.dimension())?.sky ?: return
        val drifted = driftFor(level, timeTicks)
        for (deck in spec.decks) canvas.readyCloudDeck(deck, eye, drifted)
    }

    /** Draws the level's decks, or returns false having drawn nothing so vanilla's clouds run instead. */
    fun draw(canvas: SkyCanvas, level: ClientLevel, eye: Vec3, timeTicks: Double): Boolean {
        val spec = LevelLooks.of(level.dimension())?.sky ?: return false
        if (spec.decks.isEmpty()) return false

        val drifted = driftFor(level, timeTicks)
        // Outermost last: the decks write depth, so the near one must be drawn after the far one to occlude it.
        for (deck in spec.decks) canvas.drawCloudDeck(deck, eye, drifted)
        return true
    }

    /** **The roil drifts, it does not tell the time**, so a server correction must not drag it backwards. */
    private fun driftFor(level: ClientLevel, timeTicks: Double): Double =
        SkyDrift.steady(level, "clouds", timeTicks)
}
