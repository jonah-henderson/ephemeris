package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.Mth
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * The path one celestial body travels, as a great circle around the camera.
 *
 * **Vanilla is exactly the degenerate case**, which is the property to hold on to: its sun sits at local
 * `(0, +100, 0)` under `R_y(-90°) · R_x(timeOfDay · 360°)`, so [inclinationDegrees] `= 0`,
 * [ascendingNodeDegrees] `= -90`, [distance] `= 100`, one Minecraft day.
 *
 * The composite is `R_y(ascendingNode) · R_z(inclination) · R_x(angle)`, right to left: sweep around the
 * circle, tip the plane off the zenith, then spin the arrangement about the vertical to choose which
 * compass direction the tilt leans toward.
 *
 * **The angle is eased with vanilla's own curve** (see [progressAt]): the lightmap still runs on
 * `DimensionType.timeOfDay`, so a body on a one-day period has to *be* vanilla's sun or noon would be
 * bright with the sun off to one side.
 */
data class Orbit(
    /** How far the circle is tipped out of the plane through the zenith. 0 is vanilla's overhead sweep. */
    val inclinationDegrees: Float,
    /** Which way the tilt leans, as a turn about the vertical. Vanilla's sun is at -90. */
    val ascendingNodeDegrees: Float,
    /** Where on the circle the body sits at day time zero. */
    val phaseDegrees: Float,
    /** Ticks for one revolution. [TICKS_PER_VANILLA_DAY] reproduces vanilla's rate. */
    val periodTicks: Int,
    /** Whether the body travels the other way around its circle. */
    val retrograde: Boolean,
    /** Radius of the circle. Only relative values matter — nothing here is depth-tested (see below). */
    val distance: Float,
    /**
     * How far the body's path is lifted off the great circle, toward the pole its path turns about.
     *
     * **Zero is a great circle, and a great circle is always half above the horizon and half below** — that
     * is not a property of vanilla's sun but of every circle centred on the observer, so no arrangement of
     * [inclinationDegrees] and [ascendingNodeDegrees] can ever produce a sun that does not set. A path that
     * stays up is a *small* circle, and this is what makes one: the body is carried off the great circle by
     * this much before being swept around, so it traces a cone rather than a disc.
     *
     * **It moves the body toward the pole its path turns about, and where that pole sits is the tilt's
     * business** — so the same number does different things to differently tilted paths, and the two useful
     * cases are worth spelling out because neither is guessable:
     *
     * - **Vanilla's path with `+90`** puts the sun *on the horizon, all day*. Vanilla's sun goes through the
     *   zenith, so its pole is horizontal — east and west — and walking toward that pole walks toward the
     *   horizon, not away from it.
     * - **`inclinationDegrees = 90` with any lift `L`** puts the body at a constant altitude of `L` all day.
     *   Tipping the path into the horizon plane puts its pole at the zenith, and then lift is simply height:
     *   this is the midnight sun, and `L` is how high it circles.
     *
     * Negative sinks the path the same way, until a body never rises at all. Ask [staysUp] or [neverRises]
     * rather than reading the number — what it means depends on the rest of the orbit.
     */
    val liftDegrees: Float = 0.0f,
) {

    /**
     * Where along its circle this body is, in `0.0..1.0`, at [dayTime].
     *
     * **Steps once per tick, and must never add `partialTick`.** That fraction resets to zero at every
     * tick boundary, so if the value it is added to has not incremented in the same instant each boundary
     * is a step *backwards* — a sawtooth, not a smooth arc. A level on `DerivedLevelData` takes its day time
     * from the overworld and the server syncs that only every 20 ticks, which makes it very likely. One tick
     * is 0.015° of arc anyway, so there is nothing to gain.
     *
     * The easing is `DimensionType.timeOfDay`'s, which makes a body linger near the horizon and hurry
     * through the zenith.
     */
    fun progressAt(dayTime: Long): Float {
        val revolutions = dayTime.toDouble() / periodTicks
        val travelled = if (retrograde) -revolutions else revolutions
        val raw = Mth.frac(travelled + phaseDegrees / DEGREES_PER_TURN - QUARTER_TURN)
        val eased = 0.5 - Math.cos(raw * Math.PI) / 2.0
        return ((raw * 2.0 + eased) / 3.0).toFloat()
    }

    /** The rotation placing a body at `(0, distance, 0)` onto its circle at [dayTime]. */
    fun rotationAt(dayTime: Long): Quaternionf = rotationAtProgress(progressAt(dayTime))

    /**
     * The rotation placing a body at `(0, distance, 0)` onto its circle, [progress] of the way around it.
     *
     * Separate from [rotationAt] so a body on **vanilla's own path can be turned by vanilla's own angle**
     * rather than by our reconstruction of it: the sun and moon angles are handed to the renderer, and
     * taking them is both exact and immune to vanilla moving its day curve.
     */
    fun rotationAtProgress(progress: Float): Quaternionf =
        Quaternionf()
            .rotateY(Math.toRadians(ascendingNodeDegrees.toDouble()).toFloat())
            .rotateZ(Math.toRadians(inclinationDegrees.toDouble()).toFloat())
            .rotateX(progress * TWO_PI)
            // Last, so the sweep carries it: a body pushed off the great circle *before* being swept traces
            // a small circle about the same pole. Pushed after, it would only sit at an offset on the same
            // great circle and would still set. The order is the whole of what makes a polar day possible.
            .rotateZ(Math.toRadians(-liftDegrees.toDouble()).toFloat())

    /** Where this body stands at [dayTime], as a unit direction: `+y` is up, and `y` alone decides day. */
    fun directionAt(dayTime: Long): Vector3f = directionAtProgress(progressAt(dayTime))

    fun directionAtProgress(progress: Float): Vector3f =
        rotationAtProgress(progress).transform(Vector3f(0.0f, 1.0f, 0.0f))

    /**
     * How high the body stands at [dayTime], in degrees, `-90..90`. Negative is below the horizon.
     *
     * **Vanilla's own notion of above**, which is the horizon plane and nothing subtler — no refraction, no
     * angular radius, no civil twilight. A body at exactly `0` is on the horizon and counts as up, matching
     * the way `>= 0` reads everywhere else.
     */
    fun altitudeAt(dayTime: Long): Float = altitudeAtProgress(progressAt(dayTime))

    fun altitudeAtProgress(progress: Float): Float =
        Math.toDegrees(Math.asin(directionAtProgress(progress).y.coerceIn(-1.0f, 1.0f).toDouble())).toFloat()

    /**
     * Which way it lies, as a compass bearing: degrees clockwise from **north**, so `90` is due east and
     * `270` due west.
     *
     * The negated `z` is the whole of what makes it a bearing rather than something else: Minecraft's north
     * is `-Z`, so `atan2(x, z)` would measure from *south* and read a hundred and eighty degrees out — which
     * is invisible in a check that only looks at how far a body sweeps, and puts every sunset in the wrong
     * quarter of the sky.
     */
    fun azimuthAt(dayTime: Long): Float = bearingOf(directionAt(dayTime))

    fun bearingOf(direction: Vector3f): Float {
        val degrees = Math.toDegrees(Math.atan2(direction.x.toDouble(), -direction.z.toDouble())).toFloat()
        return (degrees % FULL_TURN + FULL_TURN) % FULL_TURN
    }

    fun isUpAt(dayTime: Long): Boolean = altitudeAt(dayTime) >= 0.0f

    /**
     * Whether this path never dips below the horizon — a polar day — and whether it never reaches it.
     *
     * Answered by walking the circle rather than by solving it. The composition is three rotations deep and
     * the closed form is a trigonometric identity that is easy to get subtly wrong and impossible to read;
     * a circle sampled finely enough is exact for every purpose here and says plainly what it means. It is
     * also computed once per orbit, not per frame.
     */
    val staysUp: Boolean get() = lowest >= -GRAZING

    val neverRises: Boolean get() = highest < -GRAZING

    /** The altitudes this path reaches at its lowest and highest, in degrees. */
    val lowest: Float get() = swing().first

    val highest: Float get() = swing().second

    private fun swing(): Pair<Float, Float> {
        var least = Float.MAX_VALUE
        var most = -Float.MAX_VALUE
        for (step in 0..<SAMPLES_AROUND) {
            val altitude = altitudeAtProgress(step.toFloat() / SAMPLES_AROUND)
            if (altitude < least) least = altitude
            if (altitude > most) most = altitude
        }
        return least to most
    }

    companion object {
        const val TICKS_PER_VANILLA_DAY = 24000

        /** Vanilla's own sun, spelled out — the thing every other orbit is a departure from. */
        val VANILLA_SUN = Orbit(
            inclinationDegrees = 0.0f,
            ascendingNodeDegrees = -90.0f,
            phaseDegrees = 0.0f,
            periodTicks = TICKS_PER_VANILLA_DAY,
            retrograde = false,
            distance = 100.0f,
        )

        /**
         * Vanilla's own moon: the sun's path, half a turn behind it — but **nearer**, so that it passes in
         * front like every other moon. Only the radius departs from vanilla, and nothing reads a radius but
         * the draw order.
         */
        val VANILLA_MOON = VANILLA_SUN.copy(phaseDegrees = HALF_TURN, distance = MOON_DISTANCE)

        private const val HALF_TURN = 180.0f

        /** Inside every sun's radius. See `SkySpec.MOON_BAND`, which this sits in the middle of. */
        const val MOON_DISTANCE = 92.0f

        private const val DEGREES_PER_TURN = 360.0
        private const val TWO_PI = (Math.PI * 2).toFloat()
        private const val FULL_TURN = 360.0f

        /**
         * How finely a path is walked to find its lowest and highest points. A quarter of a degree of arc,
         * which is far finer than any consequence reads — the coarsest is "does it graze the horizon", and
         * a body moving a quarter degree per sample cannot hide a crossing inside one.
         */
        private const val SAMPLES_AROUND = 1440

        /**
         * How near the horizon still counts as touching it.
         *
         * A path that grazes the horizon exactly is one of the interesting cases rather than a pathological
         * one — it is what vanilla's own orbit does at full lift — and it is reached by a chain of rotations
         * whose exact zero lands a millionth of a degree either side. Without this the same orbit reports a
         * polar day or a sunset depending on which way the last multiplication rounded.
         */
        private const val GRAZING = 0.001f

        /**
         * `timeOfDay` is offset a quarter turn so that day time 0 is *noon*, which is vanilla's convention
         * (`DimensionType.timeOfDay(6000) == 0`) and easy to get backwards.
         */
        private const val QUARTER_TURN = 0.25

        val CODEC: Codec<Orbit> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("inclination").forGetter(Orbit::inclinationDegrees),
                Codec.FLOAT.fieldOf("ascending_node").forGetter(Orbit::ascendingNodeDegrees),
                Codec.FLOAT.fieldOf("phase").forGetter(Orbit::phaseDegrees),
                Codec.INT.fieldOf("period").forGetter(Orbit::periodTicks),
                Codec.BOOL.optionalFieldOf("retrograde", false).forGetter(Orbit::retrograde),
                Codec.FLOAT.fieldOf("distance").forGetter(Orbit::distance),
                Codec.FLOAT.optionalFieldOf("lift", 0.0f).forGetter(Orbit::liftDegrees),
            ).apply(instance, ::Orbit)
        }
    }
}
