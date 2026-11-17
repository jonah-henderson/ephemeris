package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.RuntimeLevelLog
import co.voik.ephemeris.sky.CelestialPath
import co.voik.ephemeris.sky.LevelDaylight
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.PlanetaryRings
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import org.joml.Vector3f

/**
 * A level's planetary rings, drawn over whatever drew its sky — a sibling of [AuroraPainter] and [RainbowPainter], and
 * an overlay for their reason: a level that gains rings keeps vanilla's own sun and moon, which show through them.
 *
 * **The arch never moves**, so where each point of it lies and how opaque it is are worked out once a ring and kept
 * ([Grid]). What changes is the light: each frame the sun's place decides which face is lit and where the planet's
 * shadow falls, and the daylight in front of them decides how much they wash out into the sky.
 */
object RingPainter {

    /** Draws the level's rings, if it has any and the weather lets them be seen. */
    fun draw(canvas: SkyCanvas, level: ClientLevel, moment: SkyMoment) {
        val where = level.dimension().identifier()
        val look = LevelLooks.of(level.dimension())
        if (look == null) return sayIt(level, "untold", "nothing has said what $where looks like")
        val rings = look.sky.planetaryRings
        if (rings == null) return sayIt(level, "bare", "the look for $where carries no rings")
        // How much of the sky the weather leaves, 1 when clear (see `SkyMoment.rainBrightness`).
        val clearSky = moment.rainBrightness
        if (clearSky <= WORTH_DRAWING) return sayIt(level, "weather", "rings are written, but the weather hides them")

        val nightliness = (look.air.starBrightness ?: LevelDaylight.starlitnessFor(level)
            ?: (moment.starBrightness / VANILLAS_BRIGHTEST_STARS)).coerceIn(0.0f, 1.0f)
        val dayness = 1.0f - nightliness
        val clockTime = level.defaultClockTime
        val sun = look.sky.bodies.firstOrNull { it.phase == null }
            ?.let { SkyPainter.directionOf(it, clockTime, moment.sunAngle, moment.moonAngle) }
        val colour = look.cycles.rings?.over(rings.colour, level.gameTime, moment.partOfATickOn) ?: rings.colour
        val grid = gridFor(rings)
        val corners = Array(grid.directions.size) { at ->
            val shade = rings.shadeAt(grid.alongs[at], grid.radii[at], sun) * rings.glow
            val lit = Rgba((colour.red * shade).coerceIn(0.0f, 1.0f), (colour.green * shade).coerceIn(0.0f, 1.0f), (colour.blue * shade).coerceIn(0.0f, 1.0f))
            // By day the sky's own light is in front of them and their light adds to it, so they show paler than the
            // sky as a moon does by day, never darker; by night they are their own light on the dark.
            val inFront = moment.skyColour
            val added = 1.0f - dayness * DIMMED_BY_DAY
            val seen = Rgba(
                (inFront.red * dayness + lit.red * added).coerceIn(0.0f, 1.0f),
                (inFront.green * dayness + lit.green * added).coerceIn(0.0f, 1.0f),
                (inFront.blue * dayness + lit.blue * added).coerceIn(0.0f, 1.0f),
            )
            val alpha = grid.opacities[at] * clearSky * (1.0f - dayness * THINNED_BY_DAY)
            SkyCorner(grid.directions[at], seen.copy(alpha = alpha))
        }
        val quads = ArrayList<SkyQuad>(SEGMENTS * STRIPS)
        for (segment in 0..<SEGMENTS) {
            for (strip in 0..<STRIPS) {
                val first = corners[grid.indexOf(segment, strip)]
                val second = corners[grid.indexOf(segment + 1, strip)]
                val third = corners[grid.indexOf(segment + 1, strip + 1)]
                val fourth = corners[grid.indexOf(segment, strip + 1)]
                val isVisible = maxOf(first.colour.alpha, second.colour.alpha, third.colour.alpha, fourth.colour.alpha) > INVISIBLE
                if (isVisible) quads += SkyQuad(first, second, third, fourth)
            }
        }
        if (quads.isEmpty()) return sayIt(level, "below", "rings are written, but none of them stands above the horizon")
        sayIt(level, "up", "drawing rings, crown at %.0f°".format(rings.crownAltitudeDegrees(rings.outerRadius)))
        canvas.drawShapes(quads, SkyBlend.SHADE)
    }

    /**
     * Where each corner of the arch lies and how opaque it is, by segment along it and strip across it: what never
     * changes for a ring, kept until the ring does.
     */
    private class Grid(
        val directions: Array<Vector3f>,
        val alongs: FloatArray,
        val radii: FloatArray,
        val opacities: FloatArray,
    ) {
        fun indexOf(segment: Int, strip: Int): Int = segment * (STRIPS + 1) + strip
    }

    private var kept: Pair<PlanetaryRings, Grid>? = null

    private fun gridFor(rings: PlanetaryRings): Grid {
        kept?.let { (keptFor, grid) -> if (keptFor == rings) return grid }
        val corners = (SEGMENTS + 1) * (STRIPS + 1)
        val directions = arrayOfNulls<Vector3f>(corners)
        val alongs = FloatArray(corners)
        val radii = FloatArray(corners)
        val opacities = FloatArray(corners)
        for (segment in 0..SEGMENTS) {
            val along = HALF_TURN * segment / SEGMENTS
            for (strip in 0..STRIPS) {
                val across = strip.toFloat() / STRIPS
                val radius = rings.innerRadius + (rings.outerRadius - rings.innerRadius) * across
                val at = segment * (STRIPS + 1) + strip
                val direction = rings.pointAt(along, radius).normalize()
                // Under the horizon the planet stands in the way.
                val aboveTheGround = smoothstep(HIDDEN_BELOW, SHOWN_ABOVE, CelestialPath.altitudeOf(direction))
                directions[at] = direction
                alongs[at] = along
                radii[at] = radius
                opacities[at] = rings.opacityAcross(across) * aboveTheGround
            }
        }
        val grid = Grid(directions.requireNoNulls(), alongs, radii, opacities)
        kept = rings to grid
        return grid
    }

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val progress = ((value - from) / (to - from)).coerceIn(0.0f, 1.0f)
        return progress * progress * (3.0f - 2.0f * progress)
    }

    /** One line whenever the reason changes, per level, as the other painters say theirs. */
    private fun sayIt(level: ClientLevel?, reason: String, message: String) {
        val where = level?.dimension()
        if (said[where] == reason) return
        said[where] = reason
        RuntimeLevelLog.info("Rings: $message")
    }

    private val said = mutableMapOf<ResourceKey<Level>?, String>()

    /** Along the arch, from horizon to horizon. */
    private const val SEGMENTS = 64

    /** Across it, fine enough for a narrow gap. */
    private const val STRIPS = 72

    private const val HALF_TURN = Math.PI.toFloat()

    /** Where the arch meets the ground, in degrees of altitude, faded rather than cut. */
    private const val HIDDEN_BELOW = -0.5f
    private const val SHOWN_ABOVE = 1.5f

    /** How much of their light still shows through the day's bright air, and how much thinner they look by day. */
    private const val DIMMED_BY_DAY = 0.55f
    private const val THINNED_BY_DAY = 0.2f

    private const val WORTH_DRAWING = 0.01f
    private const val INVISIBLE = 0.003f
    private const val VANILLAS_BRIGHTEST_STARS = 0.5f
}
