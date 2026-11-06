package co.voik.ephemeris.client

import co.voik.ephemeris.sky.CloudPattern
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.CloudRenderer

/**
 * A [CloudPattern]'s picture as vanilla's cloud renderer holds `clouds.png`: each cell packed with its colour and
 * which of its four neighbours are clear, exactly as `CloudRenderer.prepare` packs the file. Made once per pattern,
 * since generating one is a few million samples.
 */
object CloudPictures {

    private val made = HashMap<CloudPattern, CloudRenderer.TextureData>()

    /** The picture [level] wears in vanilla's place, its size left out, or null where it keeps vanilla's. */
    fun pictureFor(level: ClientLevel?): CloudPattern? {
        val pattern = patternOf(level) ?: return null
        return if (pattern.drawsItsOwnPicture) pattern.copy(cellScale = 1.0f) else null
    }

    /** How many times vanilla's twelve blocks a cell of [level]'s clouds is across. */
    fun cellScaleFor(level: ClientLevel?): Float = patternOf(level)?.cellScale ?: 1.0f

    /** How many times vanilla's four blocks a cell is thick: grown with its width, but by its square root. */
    fun thicknessScaleFor(level: ClientLevel?): Float = kotlin.math.sqrt(cellScaleFor(level))

    private fun patternOf(level: ClientLevel?): CloudPattern? =
        level?.let { LevelLooks.of(it.dimension())?.sky?.cloudPattern }

    /** [pattern]'s picture as vanilla's renderer reads one; its size is not part of the picture. */
    fun of(pattern: CloudPattern): CloudRenderer.TextureData = made.getOrPut(pattern.copy(cellScale = 1.0f)) {
        val side = CloudPattern.SIDE
        val cloud = pattern.cells()
        fun isClear(x: Int, z: Int) = !cloud[Math.floorMod(x, side) + Math.floorMod(z, side) * side]
        val cells = LongArray(side * side) { at ->
            val x = at % side
            val z = at / side
            if (isClear(x, z)) CLEAR else packed(isClear(x, z - 1), isClear(x + 1, z), isClear(x, z + 1), isClear(x - 1, z))
        }
        CloudRenderer.TextureData(cells, side, side)
    }

    /** A cell of white cloud and which of its sides face clear sky, as vanilla's `packCellData` packs one. */
    private fun packed(northClear: Boolean, eastClear: Boolean, southClear: Boolean, westClear: Boolean): Long {
        fun bit(clear: Boolean, at: Int) = if (clear) 1L shl at else 0L
        return (WHITE.toLong() shl COLOUR_SHIFT) or bit(northClear, NORTH) or bit(eastClear, EAST) or
            bit(southClear, SOUTH) or bit(westClear, WEST)
    }

    private const val CLEAR = 0L
    private const val WHITE = -1
    private const val COLOUR_SHIFT = 4
    private const val NORTH = 3
    private const val EAST = 2
    private const val SOUTH = 1
    private const val WEST = 0
}
