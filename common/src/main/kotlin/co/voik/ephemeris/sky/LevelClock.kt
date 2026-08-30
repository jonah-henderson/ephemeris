package co.voik.ephemeris.sky

import net.minecraft.core.Holder
import net.minecraft.util.Mth
import net.minecraft.world.clock.ClockManager
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.level.Level

/**
 * The vanilla hour whose sky looks like this one's — **the whole of making a strange sky *feel* strange**.
 *
 * 26.1 keeps every visual cue for the time of day in `Timelines.OVERWORLD_DAY`: the sky colour, the fog, the
 * cloud tint, the light's colour and factor, the sunrise band, the star brightness and the sky light level
 * are all keyframed against one clock. So a level with two suns had a working day — mobs, sleeping, block
 * light — while *looking* like the overworld's night on the overworld's schedule, because nothing in that
 * list knows a second sun exists.
 *
 * Rather than restate all of it, this answers one question: **what hour would vanilla have to be at for its
 * sky to look like ours?** Hand that to the timeline and every track follows at once, in vanilla's own
 * curves, agreeing with each other for free. Nothing here picks a colour.
 *
 * **Matched on the deciding sun's height, because height is what vanilla's own curves are keyed to.** Not on
 * its progress around its path: a sun that never sets goes right round its circle while staying up, and
 * matching progress would march the sky through a night it never has. A sky with *no* sun is the same rule
 * at its limit: nothing rises, so the hour is midnight and stays there.
 *
 * **The hour is allowed to leap, and the sky is not.** When the brightest sun changes, the answer flips from
 * the dusk side of noon to the dawn side — thousands of ticks at once — because the sky has stopped dimming
 * and started brightening, and those are different parts of vanilla's curve. It happens at equal heights, so
 * what the sky is *lit as* does not move at all: measured across one, two and three suns, never more than a
 * third of a degree of sun movement, which is the sampling rather than a seam. `LevelClockCheck` holds the
 * second property and deliberately not the first — smoothing the hour would mean keeping dusk colours while
 * the sky brightened.
 */
object LevelClock {

    /**
     * The vanilla time [look] should be lit as at [dayTime], or **null** to leave the real clock alone.
     *
     * Null only for a sky vanilla could already draw and for one that asked to keep vanilla's clock.
     *
     * **A sky with no suns is held at midnight, and that is the whole of what makes a sunless world one.**
     * It used to answer null here on the grounds that a level with nothing overhead has no daylight to
     * derive — which left every track running on the overworld's own schedule, so the Spire, which has
     * never had a sun, took vanilla's sky-light colour warm at dusk, its sunrise colour with it, and its
     * light down to nothing at midnight (Jonah, 2026-08-27, walked). There is nothing to derive because
     * there is nothing to *rise*: the answer is midnight and it is midnight at every hour.
     */
    fun vanillaEquivalent(look: LevelLook, dayTime: Long): Long? {
        if (look.sky.isOrdinary) return null
        if (look.rules.daylight == Daylight.VANILLA_CLOCK) return null
        // The day *number* is kept, so anything counting days — a moon's phase, an advancement — carries on
        // as it was. Only the hour within the day moves.
        val dayStarted = Math.floorDiv(dayTime, DAY) * DAY
        val deciding = decidingSun(look, dayTime) ?: return dayStarted + MIDNIGHT

        val height = deciding.altitudeDegrees
        val rising = deciding.body.path.altitudeAt(dayTime + LOOK_AHEAD) > height
        return dayStarted + hourAt(height, rising)
    }

    /** Which sun the level's own rule follows — the same choice `LevelDaylight` makes, and for one reason. */
    private fun decidingSun(look: LevelLook, dayTime: Long): BodyReading? {
        val reading = look.readAt(dayTime)
        return when (look.rules.daylight) {
            Daylight.EVERY_SUN -> reading.suns.maxByOrNull { it.altitudeDegrees }
            Daylight.PRIMARY_SUN -> reading.primaryUnder(look.rules)?.takeIf { it.isSun } ?: reading.suns.firstOrNull()
            Daylight.VANILLA_CLOCK -> null
        }
    }

    /**
     * The hour of a vanilla day at which its own sun stands [heightDegrees] high, on the way up or down.
     *
     * **Two answers exist and the branch matters**: vanilla's sun passes every height twice, once climbing
     * and once falling, and taking the wrong one runs the sky's whole colour sequence backwards — dusk
     * colours at dawn. So the search is confined to the half of the day that is going the same way.
     *
     * Found by bisection rather than by inverting the curve. Vanilla's easing is a cubic through a cosine and
     * its inverse is neither short nor readable; the altitude is monotonic across each half, which is all a
     * bisection needs, and this runs once a tick.
     */
    fun hourAt(heightDegrees: Float, rising: Boolean): Long {
        val height = Mth.clamp(heightDegrees, -RIGHT_ANGLE, RIGHT_ANGLE)
        // Climbing from midnight to noon, falling from noon to midnight.
        var low = if (rising) MIDNIGHT else NOON
        var high = if (rising) MIDNIGHT + HALF_DAY else NOON + HALF_DAY
        repeat(BISECTIONS) {
            val middle = (low + high) / 2
            val there = Orbit.VANILLA_SUN.altitudeAt(Math.floorMod(middle, DAY))
            val tooLow = if (rising) there < height else there > height
            if (tooLow) low = middle else high = middle
        }
        return Math.floorMod((low + high) / 2, DAY)
    }

    /** Vanilla's own day, and the two hours its sun turns around at. */
    private const val DAY = Orbit.TICKS_PER_VANILLA_DAY.toLong()
    private const val HALF_DAY = DAY / 2
    private const val NOON = 6000L
    private const val MIDNIGHT = 18000L

    private const val RIGHT_ANGLE = 90.0f

    /** Far enough ahead to tell a climb from a fall, near enough to still be the same moment. */
    private const val LOOK_AHEAD = 40L

    /** Enough to land on the tick: a day halved this many times is well under one. */
    private const val BISECTIONS = 18

    /**
     * The clock the timelines of [level] should read — **the seam, and it is one call wide**.
     *
     * `EnvironmentAttributeSystem.addDefaultLayers` hands `level.clockManager()` to every timeline sampler
     * it bakes, so substituting it here moves the sky colour, the fog, the cloud tint, the light's colour
     * and factor, the sunrise band, the star brightness and the sky light level together, in vanilla's own
     * curves. Nothing downstream is touched and nothing here picks a colour.
     *
     * Levels nothing has described get their own clock back untouched.
     */
    fun forTimelines(level: Level, real: ClockManager): ClockManager =
        MappedClock(level, real)

    /**
     * The real clock with the hour moved, and **memoised on the tick it was asked about**.
     *
     * A timeline sampler asks per attribute per frame, and the answer only changes when the level's own
     * clock does — so without this the whole sky reading would be rebuilt dozens of times a frame to return
     * the same number.
     */
    private class MappedClock(private val level: Level, private val real: ClockManager) : ClockManager {

        private var askedAbout = Long.MIN_VALUE
        private var answered = Long.MIN_VALUE

        override fun getTotalTicks(definition: Holder<WorldClock>): Long {
            val actual = real.getTotalTicks(definition)
            val look = LevelLooks.anywhere(level.dimension()) ?: return actual
            if (actual != askedAbout) {
                askedAbout = actual
                answered = vanillaEquivalent(look, actual) ?: actual
            }
            return answered
        }
    }
}
