package co.voik.ephemeris.client

import co.voik.ephemeris.RuntimeLevelLog
import net.minecraft.client.multiplayer.ClientLevel

/**
 * Watches the clocks the sky is drawn from, and says so when one of them stops running evenly.
 *
 * A sky that drifts and then steps back is reading a clock that did, and no renderer can tell from the
 * inside: the roil and the starfield take **different** numbers — `gameTime` with this frame's fraction on
 * it, and `defaultClockTime` — so if both jump together, the thing they share is underneath both of them.
 * That is what this settles, and it settles it either way.
 *
 * Two readouts, and they answer different halves:
 *
 * - **A line whenever a clock runs backwards**, carrying every other clock's reading at that instant, so
 *   whether they moved together is visible on one line rather than inferred from an interleaving.
 * - **One summary per level once it has watched a while**, whether or not anything went wrong — how evenly
 *   each clock ran, and the worst stall and jump it saw. Without it, silence would mean both "the clocks
 *   are sound" and "the sky was never drawn", which want opposite investigations.
 *
 * It is quiet after that, which is why it can simply stay.
 */
object SkyClockWatch {

    /** Ticks of backwards movement worth a line. Below this is float noise in a composed time, not a step. */
    private const val STEADY = 0.0005

    /** After this many lines about one clock, the summaries are the only readable thing left. */
    private const val ENOUGH_LINES = 40

    /**
     * Steps at which a clock restates what its steps have averaged.
     *
     * Thinning rather than periodic, because the thing that trips this is usually **not** going to stop: a
     * server that cannot keep up corrects the client's clock every couple of seconds for as long as it is
     * behind, and a line every tenth correction is a line a minute for the rest of the session. The first
     * few summaries are the ones that carry the diagnosis; after that the log should go quiet on its own.
     */
    private val WORTH_SUMMARISING_AT = setOf(10, 100, 1_000, 10_000)

    /** Readings before a level says how its clocks have been running. Around twenty seconds of frames. */
    private const val WATCHED_LONG_ENOUGH = 1200

    private const val NANOS_PER_SECOND = 1_000_000_000.0

    private val clocks = LinkedHashMap<String, Clock>()
    private val summarised = HashSet<String>()

    /**
     * Records one reading of [name] for [level].
     *
     * [wrapsAt] is the period of a clock that returns to zero — `2π` for an angle — and zero for one that
     * only ever climbs.
     */
    fun reading(
        level: ClientLevel,
        name: String,
        value: Double,
        wrapsAt: Double = 0.0,
        /**
         * **False for a reading worked out from a clock already watched**, such as a sky angle. A step in
         * one of those is the same event as the clock's, seen again, so a line per step would treble the
         * noise and carry nothing. It still earns its place in the summary, where the ratio of its ordinary
         * step to its backwards one is exactly what says whether a correction is visible.
         */
        saysEachStep: Boolean = true,
    ) {
        val where = level.dimension().identifier().toString()
        val clock = clocks.getOrPut("$where|$name") { Clock(name, wrapsAt) }
        val step = clock.take(value)
        if (step != null && saysEachStep) reportStepBack(clock, where, step)
        if (clock.readings == WATCHED_LONG_ENOUGH && summarised.add(where)) summarise(where)
    }

    private fun reportStepBack(clock: Clock, where: String, step: Double) {
        if (clock.stepsBack <= ENOUGH_LINES) {
            val gap = clock.secondsSincePreviousStep?.let { ", %.2fs after the last".format(it) } ?: ", the first"
            RuntimeLevelLog.warn(
                "Sky clock `${clock.name}` stepped BACK %.4f ticks in %s%s. Every clock now: %s"
                    .format(step, where, gap, readingsIn(where)),
            )
        }
        if (clock.stepsBack in WORTH_SUMMARISING_AT) {
            RuntimeLevelLog.warn(
                "Sky clock `${clock.name}` in %s: %d steps back, %.4f ticks each, one every %.2fs"
                    .format(where, clock.stepsBack, clock.meanStepBack, clock.meanSecondsBetweenSteps),
            )
        }
    }

    /** How each of this level's clocks has been running — said once, sound or not. */
    private fun summarise(where: String) {
        RuntimeLevelLog.info("Sky clocks over $where, after $WATCHED_LONG_ENOUGH frames:")
        for (clock in clocksIn(where)) RuntimeLevelLog.info("  ${clock.summary()}")
    }

    private fun clocksIn(where: String): List<Clock> =
        clocks.entries.filter { it.key.substringBefore('|') == where }.map { it.value }

    /**
     * Every clock's latest reading for this level, on one line.
     *
     * **The whole point of the instrument.** Whether the others moved with this one is what says a shared
     * clock is at fault rather than one renderer's arithmetic, and that cannot be read off separate lines
     * arriving in an order nothing promises.
     */
    private fun readingsIn(where: String): String =
        clocksIn(where).joinToString(", ") { "${it.name}=%.4f".format(it.last) }

    /**
     * One clock's readings: where it stands, and how evenly it has been getting there.
     *
     * [wrapsAt] is zero for a count that only climbs. An angle is the other case — it returns to zero every
     * turn, which is not a step back, so a fall of more than half a turn is read as the wrap.
     */
    private class Clock(val name: String, private val wrapsAt: Double) {
        var last = Double.NaN
            private set
        var readings = 0
            private set
        var stepsBack = 0
            private set

        private var totalStepBack = 0.0
        private var lastStepAtNanos = 0L
        private var totalGapNanos = 0L
        private var gaps = 0

        private var totalForward = 0.0
        private var forwards = 0
        private var largestForward = 0.0
        private var smallestForward = Double.MAX_VALUE

        /** Seconds between the last two steps back, or **null** where there has only been one. */
        var secondsSincePreviousStep: Double? = null
            private set

        val meanStepBack: Double get() = if (stepsBack == 0) 0.0 else totalStepBack / stepsBack
        val meanSecondsBetweenSteps: Double
            get() = if (gaps == 0) Double.NaN else totalGapNanos / gaps / NANOS_PER_SECOND

        /** How far this reading fell short of the last, or **null** where time went forwards or wrapped. */
        fun take(value: Double): Double? {
            val previous = last
            last = value
            readings++
            if (previous.isNaN()) return null

            val fall = previous - value
            val isTheWrap = wrapsAt > 0.0 && fall > wrapsAt / 2.0
            if (isTheWrap) return null
            if (fall <= STEADY) {
                recordForward(-fall)
                return null
            }
            recordStepBack(fall)
            return fall
        }

        private fun recordForward(step: Double) {
            totalForward += step
            forwards++
            if (step > largestForward) largestForward = step
            if (step < smallestForward) smallestForward = step
        }

        private fun recordStepBack(fall: Double) {
            stepsBack++
            totalStepBack += fall
            val now = System.nanoTime()
            secondsSincePreviousStep =
                if (lastStepAtNanos == 0L) null else (now - lastStepAtNanos) / NANOS_PER_SECOND
            if (lastStepAtNanos != 0L) {
                totalGapNanos += now - lastStepAtNanos
                gaps++
            }
            lastStepAtNanos = now
        }

        fun summary(): String {
            val mean = if (forwards == 0) 0.0 else totalForward / forwards
            val smallest = if (smallestForward == Double.MAX_VALUE) 0.0 else smallestForward
            val back =
                if (stepsBack == 0) "never ran backwards"
                else "ran BACK %d times, %.4f ticks each".format(stepsBack, meanStepBack)
            return "%s: %.4f ticks a frame (%.4f to %.4f), %s"
                .format(name, mean, smallest, largestForward, back)
        }
    }
}
