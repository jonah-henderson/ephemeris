package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Direction
import org.joml.Quaternionf

/**
 * The ordinary path: a circle around the camera, said in named angles.
 *
 * **Vanilla is exactly the degenerate case**, which is the property to hold on to: its sun sits at local
 * `(0, +100, 0)` under `R_y(-90°) · R_x(timeOfDay · 360°)`, so [inclinationDegrees] `= 0`,
 * [ascendingNodeDegrees] `= -90`, [distance] `= 100`, one Minecraft day.
 *
 * **This is a [Motions] with a friendlier face on it.** The four angles below become four motions and the
 * general evaluator does the work, so the convenient way to say "a circle" is provably the same thing as
 * saying it the general way — `MotionCheck` holds them to each other. What that buys is that an epicycle or
 * a wobble is not a rival implementation to keep in step, but the same machinery with a different list.
 */
data class Orbit(
    /** How far the circle is tipped out of the plane through the zenith. 0 is vanilla's overhead sweep. */
    val inclinationDegrees: Float,
    /** Which way the tilt leans, as a turn about the vertical. Vanilla's sun is at -90. */
    val ascendingNodeDegrees: Float,
    /** Where on the circle the body sits at day time zero. */
    val phaseDegrees: Float,
    /** Ticks for one revolution. [TICKS_PER_VANILLA_DAY] reproduces vanilla's rate. */
    override val periodTicks: Int,
    /** Whether the body travels the other way around its circle. */
    val retrograde: Boolean,
    /** Radius of the circle. Only relative values matter — nothing here is depth-tested. */
    override val distance: Float,
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
    /** How the sweep is paced. Vanilla's curve by default, so an ordinary orbit behaves as vanilla's does. */
    val pacing: Pacing = Pacing.VANILLAS,
) : CelestialPath {

    override val kindKey: String get() = CelestialPath.ORBIT

    /**
     * **Compared on the sweep, not on the whole record** — everything that decides *where* the body is at a
     * given tick, which is what taking vanilla's angle needs to be safe. The radius is deliberately not part
     * of it: vanilla's own moon sits nearer than its sun and is still on vanilla's path.
     */
    override val vanillas: VanillasBody?
        get() {
            val sweepsAsVanillaDoes = inclinationDegrees == 0.0f &&
                ascendingNodeDegrees == VANILLAS_NODE &&
                periodTicks == TICKS_PER_VANILLA_DAY &&
                !retrograde &&
                liftDegrees == 0.0f &&
                pacing == Pacing.VANILLAS
            if (!sweepsAsVanillaDoes) return null
            return when (phaseDegrees) {
                0.0f -> VanillasBody.SUN
                HALF_TURN -> VanillasBody.MOON
                else -> null
            }
        }

    /** Built once: `progressAt` would otherwise mint a sweep on every call, and it is asked per frame. */
    private val sweep: Motion.Sweep by lazy {
        Motion.Sweep(Direction.Axis.X, periodTicks, phaseDegrees, retrograde, pacing)
    }

    /**
     * The four motions this circle is, outermost first.
     *
     * The lift comes **last**, and that ordering is the whole of what makes a polar day possible: a body
     * pushed off the great circle *before* being swept traces a small circle about the same pole, where one
     * pushed after would only sit at an offset on the same great circle and would still set.
     */
    val motions: List<Motion>
        get() = listOf(
            Motion.Turn(Direction.Axis.Y, ascendingNodeDegrees),
            Motion.Turn(Direction.Axis.Z, inclinationDegrees),
            sweep,
            Motion.Turn(Direction.Axis.Z, -liftDegrees),
        )

    private val stack: Motions by lazy { Motions(motions, distance) }

    override fun orientationAt(dayTime: Long): Quaternionf = stack.orientationAt(dayTime)

    override fun swing(): CelestialPath.Swing = stack.swing()

    /** How far along its circle the body is at [dayTime], in `0.0..1.0`. */
    fun progressAt(dayTime: Long): Float = sweep.progressAt(dayTime)

    /**
     * The frame, [progress] of the way around the circle.
     *
     * Separate from [orientationAt] so a body on **vanilla's own path can be turned by vanilla's own angle**
     * rather than by our reconstruction of it: the sun and moon angles are handed to the renderer, and
     * taking them is both exact and immune to vanilla moving its day curve.
     */
    fun orientationAtProgress(progress: Float): Quaternionf =
        Quaternionf()
            .rotateY(Math.toRadians(ascendingNodeDegrees.toDouble()).toFloat())
            .rotateZ(Math.toRadians(inclinationDegrees.toDouble()).toFloat())
            .rotateX(progress * TWO_PI)
            .rotateZ(Math.toRadians(-liftDegrees.toDouble()).toFloat())

    fun altitudeAtProgress(progress: Float): Float =
        CelestialPath.altitudeOf(orientationAtProgress(progress).transform(org.joml.Vector3f(0.0f, 1.0f, 0.0f)))

    companion object {
        const val TICKS_PER_VANILLA_DAY = 24000

        /** Vanilla's own sun, spelled out — the thing every other path is a departure from. */
        val VANILLA_SUN = Orbit(
            inclinationDegrees = 0.0f,
            ascendingNodeDegrees = VANILLAS_NODE,
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

        /**
         * A circle rising at [bearingDegrees] — due east is `90`, the way vanilla's sun rises.
         *
         * The compass reading of an ordinary overhead path, which is the one thing a writer is most likely
         * to want to say about a sun and the one the raw angles say least clearly.
         *
         * **The node is the negated bearing**, because a turn about the vertical carries a direction the
         * other way round the compass from the angle that made it: `R_y(+θ)` takes north toward *west* as a
         * bearing is read. Vanilla's `-90` rising due east is the whole derivation, and getting the sign
         * wrong puts every sun exactly opposite where it was asked for — which reads as working until you
         * look at which horizon it came up over.
         *
         * Exact for an ordinary overhead path. A tilted one still crosses the horizon somewhere else, so
         * [from] aims the *plane* rather than promising the crossing.
         */
        fun risingAt(bearingDegrees: Float, from: Orbit = VANILLA_SUN): Orbit =
            from.copy(ascendingNodeDegrees = -bearingDegrees)

        private const val HALF_TURN = 180.0f

        /** Where vanilla's own sun leans, and the one node that means "vanilla's path". */
        const val VANILLAS_NODE = -90.0f

        /** Inside every sun's radius. See `SkySpec.MOON_BAND`, which this sits in the middle of. */
        const val MOON_DISTANCE = 92.0f

        private const val TWO_PI = (Math.PI * 2).toFloat()

        val MAP_CODEC: MapCodec<Orbit> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("inclination").forGetter(Orbit::inclinationDegrees),
                Codec.FLOAT.fieldOf("ascending_node").forGetter(Orbit::ascendingNodeDegrees),
                Codec.FLOAT.fieldOf("phase").forGetter(Orbit::phaseDegrees),
                Codec.INT.fieldOf("period").forGetter(Orbit::periodTicks),
                Codec.BOOL.optionalFieldOf("retrograde", false).forGetter(Orbit::retrograde),
                Codec.FLOAT.fieldOf("distance").forGetter(Orbit::distance),
                Codec.FLOAT.optionalFieldOf("lift", 0.0f).forGetter(Orbit::liftDegrees),
                Pacing.CODEC.optionalFieldOf("pacing", Pacing.VANILLAS).forGetter(Orbit::pacing),
            ).apply(instance, ::Orbit)
        }

        val CODEC: Codec<Orbit> = MAP_CODEC.codec()
    }
}
