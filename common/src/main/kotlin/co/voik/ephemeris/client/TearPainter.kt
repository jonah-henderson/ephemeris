package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.SkyTears
import co.voik.ephemeris.sky.TearPoint
import net.minecraft.client.multiplayer.ClientLevel

/**
 * The tears a level's sky was written with, drawn over whatever drew the sky — an overlay, as [AuroraPainter]
 * is, so an otherwise ordinary sky keeps vanilla's sun and moon. Day and night alike; only weather dims them.
 */
object TearPainter {

    private var drawnFor: SkyTears? = null
    private var points: List<TearPoint> = emptyList()

    fun draw(canvas: SkyCanvas, level: ClientLevel, rainBrightness: Float) {
        val tears = LevelLooks.of(level.dimension())?.sky?.tears ?: return
        val strength = tears.glow * rainBrightness
        if (strength <= WORTH_DRAWING) return
        canvas.drawGlows(pointsOf(tears).map { Glow(it.orientation, SkyTears.DISTANCE, it.size, it.tint.dimmed(strength)) })
    }

    /** Laid out once per set of tears, since where they hang never moves. */
    private fun pointsOf(tears: SkyTears): List<TearPoint> {
        if (tears != drawnFor) {
            points = tears.points()
            drawnFor = tears
        }
        return points
    }

    private const val WORTH_DRAWING = 0.01f
}
