package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import org.joml.Vector3f
import org.joml.Vector3fc
import kotlin.math.cos
import kotlin.math.sin

/**
 * A level's own rings, seen from beneath them: a band arching from horizon to horizon, as rings over a world's equator
 * look from its ground.
 *
 * **Real geometry, in the planet's radii.** The level stands on a planet of radius one, [latitudeDegrees] from the
 * plane its rings lie in; the rings run from [innerRadius] to [outerRadius] about its centre. Everything about where
 * the arch stands follows from that: near the ring plane it climbs to the zenith and narrows to an edge, far from it
 * it lies low along the horizon. The rings turn with their world, so the arch never moves in the sky.
 *
 * **Lit by the sun as solid things are**, which is the one thing they do through the day: the face the sun is on is
 * bright, the other dim, and where the planet stands between a ring and the sun its shadow falls across them
 * ([shadeAt]). The sun that lights them circles the planet's own axis, a season's [SEASON_DECLINATION] to the Age's
 * side of the plane, and only the time of day is read off the sun in the sky: a sun that swings overhead and
 * underfoot, as vanilla's does, would otherwise change sides of the plane every twelve hours. Data like everything
 * in a [SkySpec]; how bright the air in front of them is, is the painter's.
 */
data class PlanetaryRings(
    /** What the lit rings are coloured. */
    val colour: Rgba = ORDINARY_COLOUR,
    /** How bright they are, against ordinary rings. */
    val glow: Float = ORDINARY_GLOW,
    /** Where the rings begin, in the planet's radii from its centre. */
    val innerRadius: Float = ORDINARY_INNER,
    /** Where they end, likewise. */
    val outerRadius: Float = ORDINARY_OUTER,
    /** How far the level stands from the ring plane: low for an arch overhead, high for one along the horizon. */
    val latitudeDegrees: Float = ORDINARY_LATITUDE,
    /** How solid they are, `0..1`: thin rings with wide gaps at nought, a near-solid sheet at one. */
    val density: Float = ORDINARY_DENSITY,
    /** Which way the arch's crown stands, clockwise from north. */
    val bearingDegrees: Float = ORDINARY_BEARING,
    /** How many bands, grooves and gaps they have, and where. */
    val seed: Long = 0L,
) {

    /**
     * How opaque the rings are at [across], `0..1` from the inner edge to the outer — the bands, grooves and gaps
     * [seed] laid, scaled by [density]. Soft at both edges.
     */
    fun opacityAcross(across: Float): Float {
        if (across < 0.0f || across > 1.0f) return 0.0f
        val edges = smoothstep(0.0f, EDGE_SOFTNESS, across) * smoothstep(1.0f, 1.0f - EDGE_SOFTNESS, across)
        var opacity = profile.base(across)
        for (groove in profile.grooves) opacity *= 1.0f - groove.depth * groove.at(across)
        val gapsWiden = 1.0f + (1.0f - density) * GAPS_WIDEN_THIN
        for (gap in profile.gaps) opacity *= 1.0f - gap.depth * gap.at(across, gapsWiden)
        val solidity = MOST_SEE_THROUGH + (MOST_SOLID - MOST_SEE_THROUGH) * density.coerceIn(0.0f, 1.0f)
        return (opacity * edges * solidity).coerceIn(0.0f, 1.0f)
    }

    /**
     * Where a point of the rings lies from the eye, as a direction in the sky's frame (east +x, up +y, south +z): the
     * point [alongRadians] round the planet from the east, at [radius]. A whole turn goes round; the half from nought to
     * pi is the arch over the crown's side, and the rest is below the horizon.
     */
    fun pointAt(alongRadians: Float, radius: Float): Vector3f {
        val point = Vector3f(ringPlaneAt(alongRadians, radius)).add(PLANET_CENTRE)
        return turnedToTheBearing(point)
    }

    /**
     * How lit the rings are at a point, `0..1`, at the hour a sun in [sunDirection] tells (the sky's frame, toward the
     * sun; risen in the east at dawn, overhead at noon): the face toward the sun bright, the other dim, and nothing
     * where the planet's shadow falls. Null [sunDirection] is a sky with no sun, and the rings are faint there.
     */
    fun shadeAt(alongRadians: Float, radius: Float, sunDirection: Vector3fc?): Float {
        if (sunDirection == null) return UNLIT
        val sun = sunAtTheHourOf(sunDirection)
        val fromTheCentre = ringPlaneAt(alongRadians, radius)
        val towardTheSun = fromTheCentre.dot(sun)
        val offTheShadowAxis = Vector3f(fromTheCentre).sub(Vector3f(sun).mul(towardTheSun)).length()
        val shadowed = if (towardTheSun >= 0.0f) 0.0f else smoothstep(1.0f + SHADOW_SOFTNESS, 1.0f - SHADOW_SOFTNESS, offTheShadowAxis)
        val sunOnOurSide = sun.dot(axis)
        val facesUs = smoothstep(-FACE_SOFTNESS, FACE_SOFTNESS, sunOnOurSide)
        val lit = UNLIT_FACE + (1.0f - UNLIT_FACE) * facesUs
        return UNLIT + (lit - UNLIT) * (1.0f - shadowed)
    }

    /** How high the crown of the ring at [radius] stands above the horizon, in degrees; negative where it is below. */
    fun crownAltitudeDegrees(radius: Float): Float =
        CelestialPath.altitudeOf(pointAt(HALF_TURN / 2.0f, radius).normalize())

    private val latitude: Float get() = Math.toRadians(latitudeDegrees.toDouble()).toFloat()

    /** The planet's axis, the ring plane's normal, in the frame before the bearing turns it. */
    private val axis: Vector3f get() = Vector3f(0.0f, sin(latitude), -cos(latitude))

    /** A point of the rings from the planet's centre, in the frame before the bearing turns it. */
    private fun ringPlaneAt(alongRadians: Float, radius: Float): Vector3f {
        val inPlane = Vector3f(0.0f, cos(latitude), sin(latitude)).mul(sin(alongRadians) * radius)
        return Vector3f(cos(alongRadians) * radius, 0.0f, 0.0f).add(inPlane)
    }

    /** South turned to [bearingDegrees], clockwise from above. */
    private fun turnedToTheBearing(direction: Vector3f): Vector3f = turnedBy(direction, bearingDegrees - SOUTH)

    /**
     * Toward the sun lighting the rings, in their own frame, at the hour [sunDirection] tells: round the planet's axis
     * from the east at dawn, through the crown's side at noon, held [SEASON_DECLINATION] to the Age's side of the plane.
     */
    private fun sunAtTheHourOf(sunDirection: Vector3fc): Vector3f {
        val hour = kotlin.math.atan2(sunDirection.y(), sunDirection.x())
        val declination = Math.toRadians(SEASON_DECLINATION.toDouble()).toFloat()
        val inThePlane = Vector3f(cos(hour), 0.0f, 0.0f)
            .add(Vector3f(0.0f, cos(latitude), sin(latitude)).mul(sin(hour)))
            .mul(cos(declination))
        return inThePlane.add(Vector3f(axis).mul(sin(declination))).normalize()
    }

    private fun turnedBy(direction: Vector3f, degrees: Float): Vector3f {
        val turn = Math.toRadians(degrees.toDouble())
        val across = cos(turn).toFloat()
        val along = sin(turn).toFloat()
        return Vector3f(direction.x * across - direction.z * along, direction.y, direction.x * along + direction.z * across)
    }

    private val profile: Profile by lazy { Profile.of(seed) }

    /** The bands, grooves and gaps across the rings, drawn once from the seed. */
    private class Profile(val bands: List<Band>, val grooves: List<Band>, val gaps: List<Band>) {
        fun base(across: Float): Float = (BASE + bands.sumOf { (it.depth * it.at(across)).toDouble() }.toFloat()).coerceIn(0.0f, 1.0f)

        companion object {
            fun of(seed: Long): Profile {
                val random = XoroshiroRandomSource(seed xor PROFILE_SALT)
                fun between(least: Float, most: Float) = least + (most - least) * random.nextFloat()
                val bands = List(random.nextIntBetweenInclusive(MOST_BANDS / 2, MOST_BANDS)) {
                    Band(between(0.1f, 0.9f), between(0.08f, 0.3f), between(-0.25f, 0.35f))
                }
                val grooves = List(random.nextIntBetweenInclusive(FEWEST_GROOVES, MOST_GROOVES)) {
                    Band(between(0.03f, 0.97f), between(0.004f, 0.03f), between(0.15f, 0.45f))
                }
                val gaps = List(random.nextIntBetweenInclusive(0, MOST_GAPS)) {
                    Band(between(0.15f, 0.88f), between(0.008f, 0.035f), between(0.75f, 1.0f))
                }
                return Profile(bands, grooves, gaps)
            }
        }
    }

    /** A soft-edged stripe centred on [centre], [width] across, [depth] deep. */
    private class Band(val centre: Float, val width: Float, val depth: Float) {
        fun at(across: Float, widened: Float = 1.0f): Float {
            val half = width * widened / 2.0f
            val off = kotlin.math.abs(across - centre)
            return smoothstep(half, half * BAND_CORE, off)
        }
    }

    companion object {
        /** Pale ice and dust, a little gold, as Saturn's are. */
        val ORDINARY_COLOUR = Rgba(0.86f, 0.80f, 0.68f)
        const val ORDINARY_GLOW = 1.0f
        const val ORDINARY_INNER = 1.35f
        const val ORDINARY_OUTER = 2.25f
        const val ORDINARY_LATITUDE = 28.0f
        const val ORDINARY_DENSITY = 0.6f
        const val ORDINARY_BEARING = 180.0f

        /** How lit the side away from the sun is, against the side toward it: rings are thin, and let light through. */
        const val UNLIT_FACE = 0.35f

        /** How much shows of rings no sun lights, or in the planet's shadow. */
        const val UNLIT = 0.06f

        /** How far to the Age's side of the ring plane the sun stands, the season that keeps the near face lit. */
        const val SEASON_DECLINATION = 20.0f

        private const val SOUTH = 180.0f
        private const val HALF_TURN = Math.PI.toFloat()
        private val PLANET_CENTRE: Vector3fc = Vector3f(0.0f, -1.0f, 0.0f)

        private const val EDGE_SOFTNESS = 0.04f
        private const val SHADOW_SOFTNESS = 0.03f
        private const val FACE_SOFTNESS = 0.12f
        private const val BAND_CORE = 0.35f
        private const val BASE = 0.7f
        private const val MOST_BANDS = 4
        private const val FEWEST_GROOVES = 3
        private const val MOST_GROOVES = 14
        private const val MOST_GAPS = 4
        private const val GAPS_WIDEN_THIN = 1.5f
        private const val MOST_SEE_THROUGH = 0.3f
        private const val MOST_SOLID = 0.92f
        private const val PROFILE_SALT = 0x2196_5A7AL

        private fun smoothstep(from: Float, to: Float, value: Float): Float {
            val progress = ((value - from) / (to - from)).coerceIn(0.0f, 1.0f)
            return progress * progress * (3.0f - 2.0f * progress)
        }

        val CODEC: Codec<PlanetaryRings> = RecordCodecBuilder.create { instance ->
            instance.group(
                Rgba.CODEC.optionalFieldOf("colour", ORDINARY_COLOUR).forGetter(PlanetaryRings::colour),
                Codec.FLOAT.optionalFieldOf("glow", ORDINARY_GLOW).forGetter(PlanetaryRings::glow),
                Codec.FLOAT.optionalFieldOf("inner_radius", ORDINARY_INNER).forGetter(PlanetaryRings::innerRadius),
                Codec.FLOAT.optionalFieldOf("outer_radius", ORDINARY_OUTER).forGetter(PlanetaryRings::outerRadius),
                Codec.FLOAT.optionalFieldOf("latitude", ORDINARY_LATITUDE).forGetter(PlanetaryRings::latitudeDegrees),
                Codec.FLOAT.optionalFieldOf("density", ORDINARY_DENSITY).forGetter(PlanetaryRings::density),
                Codec.FLOAT.optionalFieldOf("bearing", ORDINARY_BEARING).forGetter(PlanetaryRings::bearingDegrees),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(PlanetaryRings::seed),
            ).apply(instance, ::PlanetaryRings)
        }
    }
}
