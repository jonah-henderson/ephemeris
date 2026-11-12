package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The shape of a level's vanilla clouds, which vanilla reads off one picture whose every pixel is a cell of cloud:
 * how much of the sky is cloud ([cover], a share, or null for vanilla's own picture), whether it lies in small bits or
 * broad patches ([clumping], -1 to 1), whether it is [torn] into rags streaming along the drift, how large a cell is
 * against vanilla's twelve blocks ([cellScale]), and how much of it is cut away in round [holes]. The picture is
 * generated here ([cells]), tiling as vanilla's does.
 */
data class CloudPattern(
    val cover: Float? = null,
    val clumping: Float = 0.0f,
    val torn: Boolean = false,
    val cellScale: Float = 1.0f,
    val seed: Long = 0L,
    /** The share of the picture cut away in round holes once it is drawn — an overcast sheet holed through. */
    val holes: Float = 0.0f,
) {
    /** Whether this asks for a picture of its own, rather than vanilla's at some size. */
    val drawsItsOwnPicture: Boolean get() = cover != null || clumping != 0.0f || torn || holes > 0.0f

    /** The picture, [SIDE] cells square, true where there is cloud: [cover] of it less its [holes], tiling at its edges. */
    fun cells(): BooleanArray = holedThrough(drawn())

    /** [picture] with round holes cut through it, about [holes] of it, each wrapping at the edges so the picture tiles. */
    private fun holedThrough(picture: BooleanArray): BooleanArray {
        val share = holes.coerceIn(0.0f, 1.0f)
        if (share <= 0.0f) return picture
        val random = Random(seed xor HOLES_SALT)
        val count = (share * SIDE * SIDE / (Math.PI * HOLE_RADIUS * HOLE_RADIUS)).roundToInt().coerceAtLeast(1)
        val reach = HOLE_RADIUS.toInt() + 1
        repeat(count) {
            val centreX = random.nextInt(SIDE)
            val centreZ = random.nextInt(SIDE)
            for (offsetZ in -reach..reach) for (offsetX in -reach..reach) {
                val isInside = offsetX * offsetX + offsetZ * offsetZ <= HOLE_RADIUS * HOLE_RADIUS
                if (isInside) picture[Math.floorMod(centreX + offsetX, SIDE) + Math.floorMod(centreZ + offsetZ, SIDE) * SIDE] = false
            }
        }
        return picture
    }

    private fun drawn(): BooleanArray {
        val random = Random(seed)
        val blob = blobPeriod()
        var alongTheDrift = if (torn) (blob * STREAMED_OUT).coerceAtMost(SIDE) else blob
        var acrossTheDrift = if (torn) (blob / TORN_THIN).coerceAtLeast(SMALLEST_PERIOD) else blob
        val roughness = if (torn) TORN_ROUGHNESS else ROUGHNESS
        val field = DoubleArray(SIDE * SIDE)
        var amplitude = 1.0
        repeat(OCTAVES) {
            val lattice = Lattice(SIDE / alongTheDrift, SIDE / acrossTheDrift, random)
            for (z in 0..<SIDE) for (x in 0..<SIDE) {
                field[x + z * SIDE] += amplitude * lattice.at(x.toDouble() / alongTheDrift, z.toDouble() / acrossTheDrift)
            }
            amplitude *= roughness
            alongTheDrift = (alongTheDrift / 2).coerceAtLeast(SMALLEST_PERIOD)
            acrossTheDrift = (acrossTheDrift / 2).coerceAtLeast(SMALLEST_PERIOD)
        }
        val share = (cover ?: VANILLA_COVER).coerceIn(0.0f, 1.0f)
        val clearCells = ((1.0f - share) * field.size).roundToInt()
        if (clearCells >= field.size) return BooleanArray(field.size)
        if (clearCells <= 0) return BooleanArray(field.size) { true }
        val threshold = field.sortedArray()[clearCells - 1]
        return BooleanArray(field.size) { field[it] > threshold }
    }

    /** How many cells across a clump is, a power of two so the lattice tiles the picture: from eight to sixty-four. */
    private fun blobPeriod(): Int {
        val wanted = ORDINARY_BLOB * BLOB_RANGE.pow(clumping.coerceIn(-1.0f, 1.0f))
        val power = kotlin.math.log2(wanted).roundToInt()
        return (1 shl power).coerceIn(SMALLEST_BLOB, LARGEST_BLOB)
    }

    /** Smoothly interpolated random values on a grid that wraps, so the picture tiles. */
    private class Lattice(private val across: Int, private val down: Int, random: Random) {
        private val values = DoubleArray(across * down) { random.nextDouble() }

        fun at(u: Double, v: Double): Double {
            val left = kotlin.math.floor(u).toInt()
            val top = kotlin.math.floor(v).toInt()
            val sideways = smooth(u - left)
            val downwards = smooth(v - top)
            fun value(x: Int, y: Int) = values[Math.floorMod(x, across) + Math.floorMod(y, down) * across]
            val upper = value(left, top) + (value(left + 1, top) - value(left, top)) * sideways
            val lower = value(left, top + 1) + (value(left + 1, top + 1) - value(left, top + 1)) * sideways
            return upper + (lower - upper) * downwards
        }

        private fun smooth(t: Double) = t * t * (3.0 - 2.0 * t)
    }

    companion object {
        /** Vanilla's picture is this many cells square, and so is ours, so it drifts across the sky as often. */
        const val SIDE = 256

        /** The share of vanilla's own picture that is cloud. */
        const val VANILLA_COVER = 0.276f

        private const val ORDINARY_BLOB = 16.0f
        private const val BLOB_RANGE = 4.0f
        private const val SMALLEST_BLOB = 8
        private const val LARGEST_BLOB = 64
        private const val SMALLEST_PERIOD = 2

        /** Torn clouds stream out along the drift and thin across it. */
        private const val STREAMED_OUT = 4
        private const val TORN_THIN = 2

        /** How far a hole reaches from its middle, in cells: a hole about seven cells across. */
        private const val HOLE_RADIUS = 3.5
        private const val HOLES_SALT = 0x484F_4C45L

        private const val OCTAVES = 3
        private const val ROUGHNESS = 0.45
        private const val TORN_ROUGHNESS = 0.7

        val CODEC: Codec<CloudPattern> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.optionalFieldOf("cover").forGetter { java.util.Optional.ofNullable(it.cover) },
                Codec.FLOAT.optionalFieldOf("clumping", 0.0f).forGetter(CloudPattern::clumping),
                Codec.BOOL.optionalFieldOf("torn", false).forGetter(CloudPattern::torn),
                Codec.FLOAT.optionalFieldOf("cell_scale", 1.0f).forGetter(CloudPattern::cellScale),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(CloudPattern::seed),
                Codec.FLOAT.optionalFieldOf("holes", 0.0f).forGetter(CloudPattern::holes),
            ).apply(instance) { cover, clumping, torn, cellScale, seed, holes ->
                CloudPattern(cover.orElse(null), clumping, torn, cellScale, seed, holes)
            }
        }
    }
}
