package co.voik.ephemeris.sky

import net.minecraft.core.Holder
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.clock.ClockInstance
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
 * **The hour is allowed to leap, and the sky is not.** When the brightest sun changes, or when the deciding
 * one turns around short of the zenith, the answer flips from the dusk side of noon to the dawn side —
 * thousands of ticks at once — because the sky has stopped dimming and started brightening, and those are
 * different parts of vanilla's curve. It happens at equal heights, so what the sky is *lit as* does not move
 * at all: measured across one, two and three suns, never more than a third of a degree of sun movement, which
 * is the sampling rather than a seam. `LevelClockCheck` holds the second property and deliberately not the
 * first — smoothing the hour would mean keeping dusk colours while the sky brightened.
 *
 * **So the moved hour says what the sky looks like and never where anything is.** Three of the same
 * timeline's tracks are the angles 26.1 places vanilla's sun, moon and stars by, and equal heights are no
 * excuse for a position: a moon short of the zenith reappeared the same distance past it, carrying the
 * starfield with it. [forTrack] is where those three are held back.
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
        val rising = deciding.body.path.altitudeAt(dayTime + LOOK_AHEAD) - height > -FLAT_BELOW
        return dayStarted + hourAt(height, rising)
    }

    /**
     * Where the hand of a clock item points in a level, as the `0..1` turn vanilla's own clock face is
     * drawn from — or **null** where the level has nothing to tell the time by and the clock should spin.
     *
     * Null for a level nothing has described, one with no skylight (roofed or lightless), and one whose sky
     * holds no sun. Anywhere else the hand follows the same hour the level is *lit as*, so it agrees with
     * the sky and with the rules the sky drives, and a sky that sits at the horizon shows the horizon.
     */
    fun clockHandFor(level: Level): Float? {
        if (!level.dimensionType().hasSkyLight()) return null
        val look = LevelLooks.anywhere(level) ?: return null
        return clockHandAt(look, level.defaultClockTime)
    }

    /**
     * The same, of a look rather than a level — pure, so it can be checked without a game.
     *
     * `Orbit.VANILLA_SUN.progressAt` is vanilla's `visual/sun_angle` over a full turn, which is what its clock
     * item reads, so the real hour and the lit-as hour are fed through the one curve.
     */
    fun clockHandAt(look: LevelLook, dayTime: Long): Float? {
        if (look.readAt(dayTime).suns.isEmpty()) return null
        val hour = vanillaEquivalent(look, dayTime) ?: dayTime
        return Orbit.VANILLA_SUN.progressAt(hour)
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

    /**
     * How far a sun may sink over [LOOK_AHEAD] and still count as holding its height, in degrees.
     *
     * A sun on a circle at constant height gains and loses a few millionths of a degree by rounding, so
     * comparing it with itself chose dawn or dusk by noise, tick by tick. Anything inside this is read as
     * climbing, so a sun that never changes height is lit as one hour. The smallest real fall is a sun at
     * the top of its arc, three thousandths of a degree.
     */
    private const val FLAT_BELOW = 1.0e-4f

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

    /** Whether [clock] is one of ours, with the hour already moved. */
    fun movesTheHour(clock: ClockManager): Boolean = clock is MappedClock

    /**
     * The clock one track should read — the moved hour for everything that says what the hour *looks* like,
     * and the real one for the three that say *where* the sky's furniture is.
     *
     * `visual/sun_angle`, `visual/moon_angle` and `visual/star_angle` sit on the same timeline as the colours
     * and are what `SkyRenderer` turns vanilla's sun, moon and starfield by. Left on the moved hour they leap
     * when it does, so a body on vanilla's own path skipped across the sky while the colours — which leap at
     * equal heights — stayed put.
     *
     * The moon's *phase* stays on the moved hour and is unharmed: the move keeps the day number, and the
     * phase only changes on a day boundary.
     */
    fun forTrack(attribute: EnvironmentAttribute<*>, clock: ClockManager): ClockManager =
        if (clock is MappedClock && placesTheSky(attribute)) clock.real else clock

    /** Whether [attribute] says **where** something in the sky is, rather than what the hour looks like. */
    fun placesTheSky(attribute: EnvironmentAttribute<*>): Boolean =
        attribute == EnvironmentAttributes.SUN_ANGLE ||
            attribute == EnvironmentAttributes.MOON_ANGLE ||
            attribute == EnvironmentAttributes.STAR_ANGLE

    /**
     * The real clock with the hour moved, and **memoised on the tick it was asked about**.
     *
     * A timeline sampler asks per attribute per frame, and the answer only changes when the level's own
     * clock does — so without this the whole sky reading would be rebuilt dozens of times a frame to return
     * the same number.
     */
    private class MappedClock(private val level: Level, val real: ClockManager) : ClockManager {

        private var askedAbout = Long.MIN_VALUE
        private var answered = Long.MIN_VALUE

        override fun getInstance(definition: Holder<WorldClock>): ClockInstance =
            MovedHour(real.getInstance(definition))

        private fun moved(actual: Long): Long {
            val look = LevelLooks.anywhere(level) ?: return actual
            if (actual != askedAbout) {
                askedAbout = actual
                answered = vanillaEquivalent(look, actual) ?: actual
            }
            return answered
        }

        /**
         * The real clock's reading with only its **hour** moved.
         *
         * 26.3 hands a whole [ClockInstance] where 26.2 answered a tick count, so the move now has to say
         * what it leaves alone as well as what it changes: the rate, the pause and the fraction through the
         * tick are the level's own and are passed straight through. Only the total moves, which is what
         * this class always did — the wider interface simply made the rest explicit.
         */
        private inner class MovedHour(private val actual: ClockInstance) : ClockInstance {
            override fun totalTicks(): Long = moved(actual.totalTicks())
            override fun partialTick(): Float = actual.partialTick()
            override fun rate(): Float = actual.rate()
            override fun isPaused(): Boolean = actual.isPaused()
        }
    }
}
