package co.voik.ephemeris.client

import co.voik.ephemeris.RuntimeLevelLog
import co.voik.ephemeris.sky.Blending
import co.voik.ephemeris.sky.CelestialPath
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.Rainbow
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.resources.Identifier

/**
 * The bow a level was written with, drawn over whatever drew its sky — the fourth sibling of [SkyPainter],
 * [CloudPainter] and [AuroraPainter].
 *
 * **An overlay rather than a claim** ([LevelRendering.skyOverlay]), for the aurora's reason: a level that
 * gains a bow keeps vanilla's own sun and moon.
 *
 * **One bow per light, and the lights are asked rather than counted.** A bow stands opposite whatever is
 * lighting it, so an Age with two suns gets two of them in different quarters of the sky and an Age with
 * none gets nothing — neither of which is a case written here. What is decided here is only how present a
 * bow is this instant; what it looks like is the spec's, and where it goes is the geometry's.
 */
object RainbowPainter {

    /**
     * Draws the level's bows, if it has any and this is a day they come.
     *
     * **Every path out says which one it took**, and a declining path says which factor was at nought —
     * the discipline `AuroraPainter` had to learn twice, because a bow that is correct and invisible has
     * even more ways to be so than a curtain does.
     */
    fun draw(canvas: SkyCanvas, sunAngle: Float, moonAngle: Float, rainBrightness: Float) {
        val level = Minecraft.getInstance().level
        if (level == null) return sayIt("nowhere", "no level to draw in")
        val where = level.dimension().identifier()
        val look = LevelLooks.of(level.dimension())
        if (look == null) return sayIt("untold", "nothing has said what $where looks like")
        val rainbow = look.sky.rainbow
        if (rainbow == null) return sayIt("bare", "the look for $where carries no bow")

        val clockTime = level.defaultClockTime
        val today = rainbow.strengthOn(clockTime / TICKS_PER_DAY)
        if (today <= WORTH_DRAWING) return sayIt("off", "a bow is written, but today is not one of its days")

        val wetEnough = rainbow.wetEnoughAt(wetnessIn(level, where, clockTime))
        if (wetEnough <= WORTH_DRAWING) {
            return sayIt("dry", "a bow is written for today, but nothing has fallen to bend its light")
        }

        // **Used as it comes, not inverted** — `rainBrightness` is how much of the sky the weather is
        // leaving, 1 when clear. It is also the whole of why a bow arrives as the rain *ends* rather than
        // during it: the air is still wet while the sky is opening, and only in that window are both high.
        // Nothing schedules the moment; it is where two curves cross.
        val clearing = rainBrightness
        if (clearing <= WORTH_DRAWING) return sayIt("downpour", "a bow wants its light, and the weather has it")

        var drawn = 0
        var highest = -QUARTER_TURN
        for (light in look.sky.bodies) {
            // **A light rather than a body**, which is what `Blending.ADDS` already means here: a thing
            // that adds itself to the sky reads as a light source, and one that covers is lit by something
            // else. So a moon written to shine casts its own pale bow and vanilla's, which does not, casts
            // nothing.
            if (light.blending != Blending.ADDS) continue
            if (drawn >= MOST_BOWS) break
            val direction = SkyPainter.directionOf(light, clockTime, sunAngle, moonAngle)
            val altitude = CelestialPath.altitudeOf(direction)
            highest = Math.max(highest, altitude)
            val cast = rainbow.castAt(altitude)
            if (cast <= WORTH_DRAWING) continue
            val strength = (today * wetEnough * clearing * cast).coerceIn(NOTHING, 1.0f)
            if (strength <= WORTH_DRAWING) continue
            canvas.drawRainbow(rainbow, altitude, CelestialPath.bearingOf(direction), strength)
            drawn++
        }
        if (drawn == 0) {
            return sayIt(
                "nothing low enough",
                "the air is wet and the day is right, but the highest light stands at %.0f° and a bow needs one under %.0f°"
                    .format(highest, rainbow.radiusDegrees),
            )
        }
        sayIt("up", "drawing $drawn bow(s), the air %.2f wet".format(wetEnough))
    }

    /**
     * How wet the air still is, `0..1` — **the one piece of state in this sky, and deliberately a small
     * one**.
     *
     * Everything else a sky does is arithmetic every client can redo from what it was already told, which
     * is why nothing about an aurora is sent or ticked. A bow cannot be: it needs to know that it rained a
     * moment ago, and how long ago is not a thing any instant carries. So the last wet moment is
     * remembered here and the rest is arithmetic again.
     *
     * Cosmetic, per client and never sent. Two players watching the same sky may see a bow fade a second
     * apart, which is a cost worth naming and not worth a packet.
     */
    private fun wetnessIn(level: ClientLevel, where: Identifier, clockTime: Long): Float {
        if (level.getRainLevel(FULL_TICK) > RAINING) {
            wetIn = where
            wettedAt = clockTime
        }
        if (wetIn != where) return NOTHING
        val since = clockTime - wettedAt
        // A clock that went backwards is a rejoin or a `/time set`, and drying takes no time at all then.
        if (since < 0L) return NOTHING
        return (1.0f - since.toFloat() / DRIES_OVER).coerceIn(NOTHING, 1.0f)
    }

    /** Where it last rained hard enough to leave the air wet, and when. */
    private var wetIn: Identifier? = null
    private var wettedAt = 0L

    /**
     * One line whenever the *reason* changes, and nothing while it stays the same.
     *
     * Keyed on the reason rather than on the message, so a fading bow does not write a line a frame while a
     * changed answer is never swallowed.
     */
    private fun sayIt(reason: String, message: String) {
        if (reason == said) return
        said = reason
        RuntimeLevelLog.info("Rainbow: $message")
    }

    private var said: String? = null

    /** Below this there is nothing on the screen and the pass is not worth opening. */
    private const val WORTH_DRAWING = 0.01f

    /** How much falling water counts as rain for the purpose of wetting the air. */
    private const val RAINING = 0.2f

    /** How long the air stays wet enough to bend light after the last of it fell — two minutes of them. */
    private const val DRIES_OVER = 2400.0f

    /** As many as any sky is worth, however many suns an Age was written with. */
    private const val MOST_BOWS = 3

    private const val FULL_TICK = 1.0f

    private const val QUARTER_TURN = 90.0f

    private const val NOTHING = 0.0f

    private const val TICKS_PER_DAY = 24000L
}
