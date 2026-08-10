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
     * Whether this path's frame is level the whole way round — **vanilla's case, and only vanilla's**.
     *
     * Vanilla sweeps about its quad's sideways axis and that axis is horizontal, so its sprite is level
     * without anything being done to it. A path like that is left exactly alone; any other is levelled by
     * [co.voik.ephemeris.sky.Facing].
     *
     * **Asked of the path and not of an instant.** A stack of motions tilts and untilts as it travels, so an
     * instant-by-instant test flickers between the two answers — and they differ, so the sprite jumps. This
     * is one decision per path, which is what makes the choice stable.
     */
    val framesAreLevel: Boolean get() = levelness()

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

    /** Whether the frame is level throughout, memoised alongside [swing] for the same reason. */
    fun levelness(): Boolean

    /** The extremes of a path, in degrees of altitude. */
    data class Swing(val lowest: Float, val highest: Float)

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

        /** How far a frame's sideways axis may leave the horizon and still count as level. */
        const val ALREADY_LEVEL = 0.001f

        /** Walks one period of [path], asking whether its frame ever leaves the horizontal. */
        fun levelnessOf(path: CelestialPath): Boolean {
            val step = (path.periodTicks.toDouble() / SAMPLES_AROUND).coerceAtLeast(1.0)
            var tick = 0.0
            while (tick < path.periodTicks) {
                val sideways = path.orientationAt(tick.toLong()).transform(Vector3f(1.0f, 0.0f, 0.0f))
                if (Math.abs(sideways.y) > ALREADY_LEVEL) return false
                tick += step
            }
            return true
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

    private val level: Boolean by lazy { CelestialPath.levelnessOf(this) }

    override fun levelness(): Boolean = level

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

    override fun levelness(): Boolean = resolved.levelness()

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
