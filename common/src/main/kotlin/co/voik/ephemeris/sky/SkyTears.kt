package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rifts torn high in a level's sky: [count] thin jagged seams of light, still and fixed above the land, each a
 * bright core in a halo of [colour]. Where they lie and how they zigzag is drawn from [seed].
 */
data class SkyTears(
    val count: Int = ORDINARY_COUNT,
    val colour: Rgba = ORDINARY_COLOUR,
    /** How brightly they burn, against an ordinary tear. */
    val glow: Float = ORDINARY_GLOW,
    val seed: Long = 0L,
) {

    /** Every point of light the tears are drawn as, at full [glow] — the same list for the same tears. */
    fun points(): List<TearPoint> {
        val random = Random(seed)
        return (0..<count.coerceIn(0, MOST_TEARS)).flatMap { pointsOfOneTear(random) }
    }

    private fun pointsOfOneTear(random: Random): List<TearPoint> {
        val bearing = random.nextDouble(0.0, FULL_TURN)
        val length = random.nextDouble(SHORTEST, LONGEST)
        // Its middle high enough that neither end reaches down past the lowest a tear may hang.
        val elevation = random.nextDouble(LOWEST_ELEVATION + length * HALF, HIGHEST_ELEVATION)
        val heading = random.nextDouble(0.0, FULL_TURN)
        val bends = List(BENDS + 1) { random.nextDouble(-1.0, 1.0) * JAGGEDNESS * length }

        // Laid out flat on a patch of sky around its middle, in degrees: along the heading, and across it.
        val along = Math.toRadians(heading)
        fun offsetAcross(share: Double): Double {
            val position = share * BENDS
            val before = position.toInt().coerceAtMost(BENDS - 1)
            val between = position - before
            return bends[before] + (bends[before + 1] - bends[before]) * between
        }

        val core = (0..CORE_POINTS).map { step ->
            val share = step.toDouble() / CORE_POINTS
            val width = taper(share)
            point(bearing, elevation, along, length, share, offsetAcross(share), CORE_SIZE * width, CORE_WHITE)
        }
        val halo = (0..HALO_POINTS).map { step ->
            val share = step.toDouble() / HALO_POINTS
            val width = taper(share)
            point(bearing, elevation, along, length, share, offsetAcross(share), HALO_SIZE * width, colour.dimmed(HALO_BRIGHTNESS))
        }
        return halo + core
    }

    private fun point(
        bearing: Double,
        elevation: Double,
        along: Double,
        length: Double,
        share: Double,
        across: Double,
        size: Double,
        tint: Rgba,
    ): TearPoint {
        val forward = (share - HALF) * length
        val east = forward * sin(along) + across * cos(along)
        val north = forward * cos(along) - across * sin(along)
        val pointBearing = bearing + east / cos(Math.toRadians(elevation)).coerceAtLeast(NEAR_THE_ZENITH)
        val pointElevation = (elevation + north).coerceAtMost(STRAIGHT_UP)
        val orientation = Quaternionf()
            .rotateY(Math.toRadians(-pointBearing).toFloat())
            .rotateX(Math.toRadians(STRAIGHT_UP - pointElevation).toFloat())
        return TearPoint(orientation, size.toFloat(), tint)
    }

    /** Thin at both ends and widest in the middle, as a split in something is. */
    private fun taper(share: Double): Double = sin(share * PI).pow(TAPER_SHARPNESS).coerceAtLeast(THINNEST)

    companion object {
        const val ORDINARY_COUNT = 3
        const val MOST_TEARS = 12
        const val ORDINARY_GLOW = 1.0f

        /** A cold violet, which reads as light coming through rather than as anything in the air. */
        val ORDINARY_COLOUR = Rgba(0.62f, 0.45f, 1.0f)

        /** How far off a point of a tear hangs, as the sky's bodies do. */
        const val DISTANCE = 100.0f

        private val CORE_WHITE = Rgba(0.95f, 0.92f, 1.0f)
        private const val HALO_BRIGHTNESS = 0.35f

        /** Half-extents in the sky's units, vanilla's sun being 30 at [DISTANCE]: a hairline, and its glow. */
        private const val CORE_SIZE = 0.28
        private const val HALO_SIZE = 1.1
        private const val CORE_POINTS = 160
        private const val HALO_POINTS = 60

        private const val LOWEST_ELEVATION = 30.0
        private const val HIGHEST_ELEVATION = 78.0
        private const val SHORTEST = 14.0
        private const val LONGEST = 32.0
        private const val BENDS = 7
        private const val JAGGEDNESS = 0.06
        private const val TAPER_SHARPNESS = 0.6
        private const val THINNEST = 0.15

        private const val FULL_TURN = 360.0
        private const val STRAIGHT_UP = 90.0
        private const val HALF = 0.5
        private const val NEAR_THE_ZENITH = 0.2

        val CODEC: Codec<SkyTears> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("count", ORDINARY_COUNT).forGetter(SkyTears::count),
                Rgba.CODEC.optionalFieldOf("colour", ORDINARY_COLOUR).forGetter(SkyTears::colour),
                Codec.FLOAT.optionalFieldOf("glow", ORDINARY_GLOW).forGetter(SkyTears::glow),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(SkyTears::seed),
            ).apply(instance, ::SkyTears)
        }
    }
}

/** One point of light a tear is drawn as: where it hangs, its half-extent at [SkyTears.DISTANCE], and its light. */
data class TearPoint(val orientation: Quaternionf, val size: Float, val tint: Rgba)
