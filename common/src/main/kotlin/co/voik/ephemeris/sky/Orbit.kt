package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.Mth
import org.joml.Quaternionf

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
            ).apply(instance, ::Orbit)
        }
    }
}
