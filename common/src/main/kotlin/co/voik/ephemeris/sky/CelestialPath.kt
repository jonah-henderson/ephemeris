package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * The path a body travels — **three tiers, and the same evaluator under all of them**.
 *
 * - [Orbit] is a circle said in named angles, which is what almost every body wants and what a datapack can
 *   read at a glance.
 * - [Motions] is a stack of turns, sweeps and wobbles. Epicycles, figures of eight, precessing orbits and
 *   spirographs are all arrangements of the same three pieces rather than cases anything was taught.
 * - [Named] is a path registered in code, for a shape no arrangement of data can express.
 *
 * **An [Orbit] *is* a [Motions]** — it builds one and evaluates through it, so the ergonomic tier is
 * provably a special case of the general one rather than a second implementation that might drift.
 * `MotionCheck` holds that.
 *
 * **Everything downstream reads only altitude and bearing**, never the shape that produced them, which is
 * why a new kind of path costs nothing outside this file: the day and night rules, the horizon glow, the
 * crossing search and the reading all go through [directionAt].
 */
sealed interface CelestialPath {

    /** The frame the body sits in at [dayTime], applied to a body at `(0, distance, 0)`. */
    fun orientationAt(dayTime: Long): Quaternionf

    /**
     * Radius of the path, where it does not vary. See [distanceAt], which is what a renderer should ask.
     */
    val distance: Float

    /**
     * How long before the path repeats, for the searches that have to bound themselves.
     *
     * The **longest** motion rather than the true common period, which for a stack of unrelated periods is
     * enormous and usually not what anyone means. A crossing search that wants certainty over a longer span
     * should say so rather than trusting this.
     */
    val periodTicks: Int

    /** Where the body stands at [dayTime], as a unit direction: `+y` is up, and `y` alone decides day. */
    fun directionAt(dayTime: Long): Vector3f =
        orientationAt(dayTime).transform(Vector3f(0.0f, 1.0f, 0.0f))

    /**
     * How high it stands, in degrees, `-90..90`. Negative is below the horizon.
     *
     * **Vanilla's own notion of above**, which is the horizon plane and nothing subtler — no refraction, no
     * angular radius, no twilight. A body at exactly `0` is on the horizon and counts as up.
     */
    fun altitudeAt(dayTime: Long): Float = altitudeOf(directionAt(dayTime))

    /**
     * Which way it lies, as a compass bearing: degrees clockwise from **north**, so `90` is due east.
     *
     * The negated `z` is what makes it a bearing rather than something else — Minecraft's north is `-Z`, so
     * `atan2(x, z)` would measure from south and put every sunset in the wrong quarter of the sky.
     */
    fun bearingAt(dayTime: Long): Float = bearingOf(directionAt(dayTime))

    /**
     * How far away the body is at [dayTime]. Constant unless a path says otherwise.
     *
     * **Apparent size follows this for nothing.** The quad is drawn at this distance under a real
     * projection, so a body that comes nearer is simply bigger on the screen — which is what makes an
     * elliptical path read as one rather than as a body that merely changes draw order.
     */
    fun distanceAt(dayTime: Long): Float = distance

    fun isUpAt(dayTime: Long): Boolean = altitudeAt(dayTime) >= 0.0f

    /**
     * Which of vanilla's own bodies this path *is*, so a renderer can turn it by vanilla's own angle rather
     * than by a reconstruction of it — exact, and immune to vanilla moving its day curve, which it has.
     *
     * Asked as a property rather than by comparing against a constant, so a body on vanilla's path at some
     * other radius still gets vanilla's angle. Null for every other path.
     */
    val vanillas: VanillasBody? get() = null

    /**
     * The turn, in radians about the line of sight, that lays this path's sprite level at [dayTime] — the
     * square's horizontal edges along the horizon, wherever the body stands and whatever the path.
     *
     * **Level names the sprite only up to a half turn, and that spare half turn is the whole difficulty.**
     * Both answers lay the edges on the horizon and differ only in which way up the sprite ends. Taking the
     * upright one afresh at every instant snaps the sprite end for end as a body crosses the zenith, where
     * upright stops meaning anything (Jonah, 2026-08-29, walked, on a moon rising south and setting north).
     * Following one answer round smoothly never snaps, and pays for that by letting the sprite go upside
     * down — which is what vanilla's own crescent does between its rising and its setting.
     *
     * **Smooth is also the least turning available.** Every other choice is this one with half turns
     * inserted where it jumps, and each of those spends a half turn's movement this does not. See
     * [levellingOf], which walks it.
     */
    fun levellingTurnAt(dayTime: Long): Float = levelling().at(dayTime)

    /** Never dips below the horizon — a polar day. */
    val staysUp: Boolean get() = swing().lowest >= -GRAZING

    /** Never reaches it. */
    val neverRises: Boolean get() = swing().highest < -GRAZING

    val lowest: Float get() = swing().lowest

    val highest: Float get() = swing().highest

    /**
     * The altitudes this path reaches at its extremes, **found by walking it rather than by solving it**.
     *
     * The closed form is a trigonometric identity as deep as the stack is tall, easy to get subtly wrong and
     * impossible to read, and it has to special-case every path that never crosses at all. Walking is exact
     * to within a step and says plainly what it means.
     *
     * Implementations are expected to **memoise** this: it is a property, so it reads like a field, and a
     * caller looping over bodies would otherwise pay a full walk per body per look.
     */
    fun swing(): Swing

    /** The turns that level the sprite, memoised alongside [swing] for the same reason. */
    fun levelling(): Levelling

    /** The extremes of a path, in degrees of altitude. */
    data class Swing(val lowest: Float, val highest: Float)

    /**
     * The levelling turn sampled around one period, **already unwrapped** — consecutive entries are one
     * answer followed round rather than each sample's own nearest upright, so reading it back cannot
     * produce a snap the walk did not have.
     *
     * Entries are ordinary radians and may run outside a single turn, which is the point: a body circling
     * the zenith accumulates, and folding that back into one turn is exactly the jump being avoided.
     */
    class Levelling(private val turns: FloatArray, private val periodTicks: Int) {

        /**
         * Interpolated between the two samples either side of [dayTime].
         *
         * Across the seam those two are a period apart and may stand whole half turns from each other, so
         * the nearer reading is taken — the short way round, rather than a period's accumulation unwound in
         * one sample's worth of time.
         */
        fun at(dayTime: Long): Float {
            val period = periodTicks.coerceAtLeast(1)
            val place = ((dayTime % period + period) % period).toFloat() / period * turns.size
            val below = place.toInt() % turns.size
            val above = (below + 1) % turns.size
            val from = turns[below]
            return from + (nearestHalfTurnTo(from, turns[above]) - from) * (place - place.toInt())
        }
    }

    companion object {
        /**
         * How near the horizon still counts as touching it.
         *
         * A path that grazes the horizon exactly is one of the interesting cases rather than a pathological
         * one — it is what vanilla's own orbit does at full lift — and it is reached by a chain of rotations
         * whose exact zero lands a millionth of a degree either side. Without this the same path reports a
         * polar day or a sunset depending on which way the last multiplication rounded.
         */
        const val GRAZING = 0.001f

        /**
         * How finely a path is walked to find its extremes. A quarter of a degree of arc on a vanilla day,
         * far finer than any consequence reads.
         */
        const val SAMPLES_AROUND = 1440

        private const val FULL_TURN = 360.0f

        fun altitudeOf(direction: Vector3f): Float =
            Math.toDegrees(Math.asin(direction.y.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()

        fun bearingOf(direction: Vector3f): Float {
            val degrees = Math.toDegrees(Math.atan2(direction.x.toDouble(), -direction.z.toDouble())).toFloat()
            return (degrees % FULL_TURN + FULL_TURN) % FULL_TURN
        }

        /**
         * Walks one period of [path] and answers the turns that lay its sprite level all the way round —
         * which every implementation's [levelling] should memoise.
         *
         * The frame's sideways axis is `cos t · sideways - sin t · upward` once turned by `t`, so laying it
         * on the horizon is asking for the `t` that zeroes the vertical part of that, which `atan2` gives
         * outright — but only up to the half turn that zeroes it just as well the other way up.
         *
         * **The walk anchors at the rising and unwraps from there.** `atan2`'s own answer is the upright
         * one, so a body comes up the right way up; every later sample is that sample's answer shifted by
         * whole half turns to sit nearest the one before it, which is what follows a single answer round
         * instead of re-choosing at each step. A path that never rises is anchored where it comes closest
         * to the horizon, that being the nearest thing it has to a rising.
         *
         * **It can only follow what it can see.** A body skimming within about half a degree of the zenith
         * turns faster than [SAMPLES_AROUND] resolves, and the walk may take the wrong half turn there. A
         * path *through* the zenith is not that case and is exact: its sideways axis is already horizontal,
         * so there is nothing to follow.
         */
        fun levellingOf(path: CelestialPath): Levelling {
            val samples = SAMPLES_AROUND
            val upright = FloatArray(samples) { sample -> uprightTurnOf(path, tickOf(path, sample, samples)) }
            val turns = FloatArray(samples)
            val rising = risingSampleOf(path, samples)
            turns[rising] = upright[rising]
            for (step in 1..<samples) {
                val sample = (rising + step) % samples
                val before = turns[(rising + step - 1) % samples]
                turns[sample] = nearestHalfTurnTo(before, upright[sample])
            }
            return Levelling(turns, path.periodTicks)
        }

        /**
         * The upright answer at [dayTime]: level, and with the sprite's own upward axis above the horizon
         * rather than below it. One of the two half turns that level the sprite, and the one a rising wants.
         */
        private fun uprightTurnOf(path: CelestialPath, dayTime: Long): Float {
            val frame = path.orientationAt(dayTime)
            val sideways = frame.transform(Vector3f(1.0f, 0.0f, 0.0f))
            val upward = frame.transform(Vector3f(0.0f, 0.0f, 1.0f))
            return Math.atan2(sideways.y.toDouble(), upward.y.toDouble()).toFloat()
        }

        private fun tickOf(path: CelestialPath, sample: Int, samples: Int): Long =
            sample.toLong() * path.periodTicks / samples

        /**
         * The sample [path] comes up on — the first to cross the horizon climbing.
         *
         * **A rising and a setting are half a turn apart**, which is exactly the difference the sprite is
         * meant to show, so the two cannot be used interchangeably. A path that never crosses at all is
         * anchored where it comes closest to the horizon.
         */
        private fun risingSampleOf(path: CelestialPath, samples: Int): Int {
            var lowestAt = 0
            var lowest = Float.MAX_VALUE
            var wasDown = path.altitudeAt(tickOf(path, samples - 1, samples)) < 0.0f
            for (sample in 0..<samples) {
                val altitude = path.altitudeAt(tickOf(path, sample, samples))
                if (wasDown && altitude >= 0.0f) return sample
                if (altitude < lowest) {
                    lowest = altitude
                    lowestAt = sample
                }
                wasDown = altitude < 0.0f
            }
            return lowestAt
        }

        /** Walks one period of [path], which is what every implementation's [swing] should memoise. */
        fun swingOf(path: CelestialPath): Swing {
            var least = Float.MAX_VALUE
            var most = -Float.MAX_VALUE
            val step = (path.periodTicks.toDouble() / SAMPLES_AROUND).coerceAtLeast(1.0)
            var tick = 0.0
            while (tick < path.periodTicks) {
                val altitude = path.altitudeAt(tick.toLong())
                if (altitude < least) least = altitude
                if (altitude > most) most = altitude
                tick += step
            }
            return Swing(least, most)
        }

        /**
         * **Lazy, and it has to be.** An interface with default methods is initialised when a class
         * implementing it is, so touching `Orbit.VANILLA_SUN` starts `Orbit`, which starts `CelestialPath`,
         * which would reach back for `Orbit.MAP_CODEC` while `Orbit`'s own companion is half-built — and
         * fail with a null companion, from a stack trace naming neither cycle nor cause.
         */
        private val KINDS: Map<String, MapCodec<out CelestialPath>> by lazy {
            mapOf(
                ORBIT to Orbit.MAP_CODEC,
                MOTIONS to Motions.MAP_CODEC,
                NAMED to Named.MAP_CODEC,
            )
        }

        /**
         * Dispatched on a `kind` field, defaulting to [Orbit] so that every spec written before paths
         * existed still reads — those carry a bare orbit with no kind at all.
         */
        val CODEC: Codec<CelestialPath> by lazy {
            Codec.STRING.dispatch("kind", CelestialPath::kindKey) { key -> KINDS[key] ?: Orbit.MAP_CODEC }
        }

        const val ORBIT = "orbit"
        const val MOTIONS = "motions"
        const val NAMED = "named"
    }

    val kindKey: String
}

/** [turn] shifted by whole half turns to land as near [previous] as it can. */
private fun nearestHalfTurnTo(previous: Float, turn: Float): Float =
    turn + Math.round((previous - turn) / HALF_TURN_RADIANS) * HALF_TURN_RADIANS

private val HALF_TURN_RADIANS = Math.PI.toFloat()

/** One of vanilla's own two bodies, for a path that is exactly its path. */
enum class VanillasBody { SUN, MOON }

/**
 * A path built straight from a stack of motions — **the general case, and what every other kind evaluates
 * through**.
 *
 * Nothing here knows what a circle is. That is the point: an [Orbit] hands over the four motions it means
 * and this evaluates them, so there is one evaluator and the ergonomic tier cannot drift from the general
 * one.
 */
data class Motions(
    val motions: List<Motion>,
    override val distance: Float = VANILLA_DISTANCE,
) : CelestialPath {

    override val kindKey: String get() = CelestialPath.MOTIONS

    override val periodTicks: Int
        get() = motions.mapNotNull { it.periodTicks }.maxOrNull() ?: Orbit.TICKS_PER_VANILLA_DAY

    override fun orientationAt(dayTime: Long): Quaternionf =
        motions.fold(Quaternionf()) { frame, motion -> motion.turn(frame, dayTime) }

    private val walked: CelestialPath.Swing by lazy { CelestialPath.swingOf(this) }

    override fun swing(): CelestialPath.Swing = walked

    private val levelledBy: CelestialPath.Levelling by lazy { CelestialPath.levellingOf(this) }

    override fun levelling(): CelestialPath.Levelling = levelledBy

    companion object {
        const val VANILLA_DISTANCE = 100.0f

        val MAP_CODEC: MapCodec<Motions> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Motion.CODEC.listOf().fieldOf("motions").forGetter(Motions::motions),
                Codec.FLOAT.optionalFieldOf("distance", VANILLA_DISTANCE).forGetter(Motions::distance),
            ).apply(instance, ::Motions)
        }
    }
}

/**
 * A path registered in code, named on the wire — **the escape hatch**.
 *
 * For a shape no arrangement of motions can express: one read from a curve, driven by something outside the
 * sky, or simply easier to write as arithmetic. The id crosses the network and both ends look it up, so
 * **a level using one is only drawn correctly by a client that has the mod that registered it** — which is
 * the trade, and why the data tiers exist above it.
 *
 * An unregistered id falls back to vanilla's own path rather than throwing: a sky in the wrong place beats a
 * client that cannot draw a frame.
 */
data class Named(val id: Identifier) : CelestialPath {

    override val kindKey: String get() = CelestialPath.NAMED

    private val resolved: CelestialPath get() = CelestialPaths.of(id)

    override val distance: Float get() = resolved.distance

    override val periodTicks: Int get() = resolved.periodTicks

    override fun orientationAt(dayTime: Long): Quaternionf = resolved.orientationAt(dayTime)

    override fun swing(): CelestialPath.Swing = resolved.swing()

    override fun levelling(): CelestialPath.Levelling = resolved.levelling()

    companion object {
        val MAP_CODEC: MapCodec<Named> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Named::id),
            ).apply(instance, ::Named)
        }
    }
}

/**
 * Paths supplied in code, by name.
 *
 * **Both ends must register the same names**, the server to reason about day and night and a client to draw
 * the sky. Registering from common init rather than from either side's is the way to be sure.
 */
object CelestialPaths {

    private val registered = mutableMapOf<Identifier, CelestialPath>()

    fun register(id: Identifier, path: CelestialPath) {
        registered[id] = path
    }

    /** The path registered under [id], or vanilla's own where nothing is. */
    fun of(id: Identifier): CelestialPath = registered[id] ?: Orbit.VANILLA_SUN
}
