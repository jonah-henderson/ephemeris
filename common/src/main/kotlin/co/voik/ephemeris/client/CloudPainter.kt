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

    /** Draws the level's decks, or returns false having drawn nothing so vanilla's clouds run instead. */
    fun draw(canvas: SkyCanvas, level: ClientLevel, eye: Vec3, timeTicks: Float): Boolean {
        val spec = LevelLooks.of(level.dimension())?.sky ?: return false
        if (spec.decks.isEmpty()) return false

        // Outermost last: the decks write depth, so the near one must be drawn after the far one to occlude it.
        for (deck in spec.decks) canvas.drawCloudDeck(deck, eye, timeTicks)
        return true
    }
}
