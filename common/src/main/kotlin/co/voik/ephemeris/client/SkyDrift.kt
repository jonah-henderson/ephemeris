package co.voik.ephemeris.client

import net.minecraft.client.multiplayer.ClientLevel

/**
 * A level's clock with the lurches taken out, for the things in the sky that merely **drift**.
 *
 * A client runs its own clock forward at twenty ticks a second and the server corrects it. While the server
 * keeps up there is nothing to correct, but a server running behind drops ticks, so the correction arrives
 * as a **step backwards** — measured at 25 ticks every 2.3 seconds on an overloaded one, which is more than
 * a second of motion undone several times a minute (`SkyClockWatch`, 2026-09-20).
 *
 * The roil, the twinkle and an aurora's fold have no business following that. They are not *telling* the
 * time — nothing about them answers a question, and a viewer comparing them against the clock is not a
 * thing that happens. So they run on this instead: the level's own clock while it goes forwards, and held
 * still where it goes back.
 *
 * **What genuinely says the hour keeps the real clock**, and that is the whole of the distinction. Where a
 * sun stands, which night an aurora is having, what phase a moon is at — those are read against the world
 * and have to be wrong when the world is wrong, or they answer a question with a lie. They also survive it:
 * the same correction moves a sun by four hundredths of a degree, because a day is 24000 ticks long and
 * these are 25.
 */
object SkyDrift {

    /**
     * The most a single frame may carry a drift forward, in ticks.
     *
     * Backwards steps are the fault being fixed, but the same lag throws big steps *forwards* too — a
     * client catching up, or a level whose clock was simply set. Letting one frame swallow that would
     * put the lurch back in facing the other way. Four ticks is a whole frame at five a second, far more
     * than anything healthy asks for, and far less than a correction.
     */
    private const val MOST_A_FRAME_MAY_CARRY = 4.0

    private val drifts = HashMap<String, Drift>()

    /**
     * How far [name]'s drift has come for [level], given the level clock's reading of [ticks].
     *
     * Asking twice in a frame is safe and is what the painters do: the second reading has not moved, so it
     * carries the drift nowhere.
     */
    fun steady(level: ClientLevel, name: String, ticks: Double): Double {
        val where = level.dimension().identifier().toString()
        return drifts.getOrPut("$where|$name") { Drift(ticks) }.carriedTo(ticks)
    }

    private class Drift(start: Double) {
        private var lastSeen = start
        private var come = start

        fun carriedTo(ticks: Double): Double {
            val step = ticks - lastSeen
            lastSeen = ticks
            come += step.coerceIn(0.0, MOST_A_FRAME_MAY_CARRY)
            return come
        }
    }
}
