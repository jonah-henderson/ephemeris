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
 * **What genuinely says the hour keeps the real clock**, and that is the whole of the distinction. Which
 * night an aurora is having, what phase a moon is at — those are read against the world and have to be
 * wrong when the world is wrong, or they answer a question with a lie.
 *
 * **The sky's rotation is the one thing that is both**, and [turning] is its own rule for that reason: held
 * through a correction rather than carried past it, so it never runs backwards *and* never runs later than
 * the clock has genuinely read. It needed a rule of its own because the sky turns so slowly that a
 * correction is sixteen frames of motion undone at once, which reads as a ratchet however small the number
 * is.
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

    /**
     * How far behind its own highest reading a turn may fall before this follows it down.
     *
     * A lag correction moves the sky a hundredth of a degree; a quarter turn is five thousand ticks. So
     * anything this size is the level's time being **set**, not corrected, and waiting for the clock to
     * come back round to where the sky already stands would hold it still for minutes.
     */
    private const val A_TIME_CHANGE = Math.PI / 2.0

    /**
     * A fall this big is the angle coming round again rather than the clock going back.
     *
     * Tight on purpose: a wrap falls by very nearly a whole turn, and a correction by a fraction of a
     * degree, so there is a wide gap to put the bar in and no reason to put it near the middle.
     */
    private const val MOST_OF_A_TURN = 2.0 * Math.PI * 0.75

    private const val A_WHOLE_TURN = 2.0 * Math.PI

    private val drifts = HashMap<String, Drift>()
    private val turnings = HashMap<String, Turning>()

    /**
     * [angle] with the ratchet taken out — **held while the clock is being corrected, and never ahead of
     * what the clock has actually said**.
     *
     * The sky's rotation is the one thing here that both *drifts* and *tells the hour*, so neither rule
     * elsewhere in this file fits it. It cannot simply carry on through a correction the way the roil does,
     * or a sun would creep permanently later than the world it stands over. It equally cannot take the
     * correction as given: the field turns a ten-thousandth of a radian in a frame and the correction is
     * sixteen times that, so a star creeps forward and then visibly jumps back — measured as 21 ratchets in
     * 1200 frames over the Spire, 2026-09-20.
     *
     * So it holds instead. The sky never runs backwards and never runs later than the clock has genuinely
     * read, which leaves a pause where there was a jerk; a pause in something this slow cannot be seen, and
     * the reversal plainly could.
     *
     * **Every angle of one sky must go through this together**, or a sun and the stars behind it would hold
     * by different amounts and drift apart — which is why the sun's is steadied even though nobody reported
     * the sun.
     */
    fun turning(level: ClientLevel, name: String, angle: Float): Float {
        val where = level.dimension().identifier().toString()
        return turnings.getOrPut("$where|$name") { Turning(angle.toDouble()) }.steadiedAt(angle.toDouble())
    }

    private class Turning(start: Double) {
        private var lastRaw = start
        private var turns = 0
        private var shown = start

        fun steadiedAt(raw: Double): Float {
            if (raw < lastRaw - MOST_OF_A_TURN) turns++
            lastRaw = raw
            val real = raw + turns * A_WHOLE_TURN
            shown = if (real < shown - A_TIME_CHANGE) real else maxOf(shown, real)
            return (shown % A_WHOLE_TURN).toFloat()
        }
    }

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
