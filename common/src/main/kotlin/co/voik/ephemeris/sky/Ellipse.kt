package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Direction
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A path that comes up over one horizon and goes down under another, wherever the two are: a body rising in the north
 * and setting in the east.
 *
 * **Day and night stay what they were**, half the turn each and paced as [pacing] says, and the body covers half the
 * sky in each at a steady rate. Seen from above, its day is half an ellipse pinned at the two points; its night is the
 * same arc mirrored under the horizon and walked back. Two points opposite each other flatten the ellipse to the
 * ordinary path through the zenith; one point twice flattens it to a line, so the body climbs to overhead at noon and
 * turns back down, and at midnight reaches straight underfoot and turns back up. Between those the noon peak falls
 * short of overhead, since a path half the sky long cannot reach it without a corner.
 */
data class Ellipse(
    /** The bearing it comes up at, clockwise from north. */
    val risingDegrees: Float,
    /** The bearing it goes down at. */
    val settingDegrees: Float,
    override val periodTicks: Int,
    /** Where on its way round the body is at day time zero, as [Orbit.phaseDegrees] says it. */
    val phaseDegrees: Float,
    override val distance: Float,
    val pacing: Pacing = Pacing.VANILLAS,
) : CelestialPath {

    override val kindKey: String get() = CelestialPath.ELLIPSE

    private val sweep: Motion.Sweep by lazy { Motion.Sweep(Direction.Axis.X, periodTicks, phaseDegrees, pacing = pacing) }

    private val day: DayArc by lazy { DayArc.between(horizonAt(risingDegrees), horizonAt(settingDegrees)) }

    override fun paced(factor: Double): CelestialPath = copy(periodTicks = pacedPeriod(periodTicks, factor))

    override fun orientationAt(dayTime: Long): Quaternionf = Quaternionf().rotationTo(UP, directionAtProgress(sweep.progressAt(dayTime)))

    /**
     * Where the body stands [progress] of the way round, `0.0..1.0` from noon as a [Motion.Sweep] counts it: up at a
     * quarter from three quarters, down from a quarter to three quarters.
     */
    fun directionAtProgress(progress: Float): Vector3f {
        val fromRising = ((progress.toDouble() + QUARTER) % WHOLE + WHOLE) % WHOLE
        return if (fromRising < HALF) day.at(fromRising / HALF) else mirrored(day.at(WHOLE - (fromRising - HALF) / HALF))
    }

    private val walked: CelestialPath.Swing by lazy { CelestialPath.swingOf(this) }
    private val levelledBy: CelestialPath.Levelling by lazy { CelestialPath.levellingOf(this) }
    private val rose: List<Long> by lazy { CelestialPath.risingsOf(this) }

    override fun swing(): CelestialPath.Swing = walked

    override fun levelling(): CelestialPath.Levelling = levelledBy

    override fun risings(): List<Long> = rose

    /**
     * The day's half of the path, from the rising (0) to the setting (1) at an even pace along it.
     *
     * Laid out in the horizontal plane — `x` east, `z` south — as `centre + sin φ · along + cos φ · bulge` for φ from
     * a quarter turn before noon to a quarter after, lifted onto the sky over each point. [bulge] leans the noon
     * point from the middle of the two horizon points back toward the zenith, as far as makes the arc half the sky
     * long; where the two are opposite there is no middle to lean from and the arc is the ordinary one.
     */
    private class DayArc(private val centre: Flat, private val along: Flat, private val bulge: Flat) {
        private val sampled: List<Vector3f> = (0..SAMPLES).map { at -> lifted(PI * at / SAMPLES - PI / 2.0) }
        private val travelled: DoubleArray = DoubleArray(SAMPLES + 1).also { lengths ->
            for (at in 1..SAMPLES) lengths[at] = lengths[at - 1] + CelestialPath.angleBetween(sampled[at - 1], sampled[at])
        }

        /** The point [fraction] of the way along the arc, by distance travelled. */
        fun at(fraction: Double): Vector3f {
            val wanted = fraction.coerceIn(0.0, 1.0) * travelled.last()
            val found = travelled.binarySearch(wanted)
            val after = (if (found >= 0) found else -found - 1).coerceIn(1, SAMPLES)
            val before = after - 1
            val span = travelled[after] - travelled[before]
            val between = if (span <= 0.0) 0.0 else (wanted - travelled[before]) / span
            return Vector3f(sampled[before]).lerp(sampled[after], between.toFloat()).normalize()
        }

        private fun lifted(phi: Double): Vector3f {
            val x = centre.x + sin(phi) * along.x + cos(phi) * bulge.x
            val z = centre.z + sin(phi) * along.z + cos(phi) * bulge.z
            val up = sqrt((1.0 - x * x - z * z).coerceAtLeast(0.0))
            return Vector3f(x.toFloat(), up.toFloat(), z.toFloat()).normalize()
        }

        val length: Double get() = travelled.last()

        companion object {
            fun between(rising: Flat, setting: Flat): DayArc {
                val centre = Flat((rising.x + setting.x) / 2.0, (rising.z + setting.z) / 2.0)
                val along = Flat((setting.x - rising.x) / 2.0, (setting.z - rising.z) / 2.0)
                fun leaning(share: Double) = DayArc(centre, along, Flat(-centre.x * share, -centre.z * share))
                if (leaning(ALL_THE_WAY).length <= PI) return leaning(ALL_THE_WAY)
                var least = 0.0
                var most = ALL_THE_WAY
                repeat(SEARCH_STEPS) {
                    val middle = (least + most) / 2.0
                    if (leaning(middle).length < PI) least = middle else most = middle
                }
                return leaning((least + most) / 2.0)
            }
        }
    }

    /** A point in the horizontal plane. */
    private data class Flat(val x: Double, val z: Double)

    companion object {
        val MAP_CODEC: MapCodec<Ellipse> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("rising").forGetter(Ellipse::risingDegrees),
                Codec.FLOAT.fieldOf("setting").forGetter(Ellipse::settingDegrees),
                Codec.INT.fieldOf("period").forGetter(Ellipse::periodTicks),
                Codec.FLOAT.optionalFieldOf("phase", 0.0f).forGetter(Ellipse::phaseDegrees),
                Codec.FLOAT.fieldOf("distance").forGetter(Ellipse::distance),
                Pacing.CODEC.optionalFieldOf("pacing", Pacing.VANILLAS).forGetter(Ellipse::pacing),
            ).apply(instance, ::Ellipse)
        }

        private val UP = Vector3f(0.0f, 1.0f, 0.0f)

        /** The horizon at [bearingDegrees] clockwise from north, where north is `-z`. */
        private fun horizonAt(bearingDegrees: Float): Flat {
            val radians = Math.toRadians(bearingDegrees.toDouble())
            return Flat(sin(radians), -cos(radians))
        }

        private fun mirrored(direction: Vector3f): Vector3f = Vector3f(direction.x, -direction.y, direction.z)

        /** How finely the day's arc is walked to pace the body evenly along it. */
        private const val SAMPLES = 720

        /** Halvings of the bulge searched for the arc half the sky long; far past a block's worth on any sky. */
        private const val SEARCH_STEPS = 40

        /** A bulge leaning the noon point all the way to the zenith. */
        private const val ALL_THE_WAY = 1.0

        private const val WHOLE = 1.0
        private const val HALF = 0.5
        private const val QUARTER = 0.25
    }
}
