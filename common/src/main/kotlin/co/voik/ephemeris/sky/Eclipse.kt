package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One sun being covered at one instant: which body of the spec it is, how much of its disc is hidden, `0..1`, and
 * by which bodies.
 *
 * **A pure function of the spec and the clock**, as [SkyReading] is, so the server can darken the light the game
 * reads and the client can darken the sky by the same number without anything crossing the wire.
 *
 * It answers by the rules the sky is drawn by: a body hides a sun only if it [Blending.COVERS] and stands nearer,
 * and only across their discs, never their glows.
 */
data class Eclipse(val sun: Int, val coverage: Float, val coveredBy: List<Int>) {

    /** Whether the whole disc is hidden, which only a covering body at least the sun's apparent size can do. */
    val isTotal: Boolean get() = coverage >= TOTAL

    /** How far toward night an eclipse this deep takes the day, `0..1` — see [shadowOf]. */
    val shadow: Float get() = shadowOf(coverage)

    companion object {
        /**
         * How far toward night a sun [coverage] covered takes the day: nothing until half the disc is gone, then
         * steeply, as an eclipse is, to night itself at totality. What the light the game reads and the air the
         * eye sees both follow, so the two cannot disagree.
         */
        fun shadowOf(coverage: Float): Float {
            val pastHalf = ((coverage - SHADOW_FROM) / (1.0f - SHADOW_FROM)).coerceIn(0.0f, 1.0f)
            return pastHalf * pastHalf * pastHalf
        }

        /**
         * Where the last of [eclipse]'s sun shines from at [dayTime]: the point of its rim farthest from the body
         * covering it, and the sun's angular radius in radians. Null where that body is smaller than the sun, whose
         * last light is a whole ring rather than a point.
         */
        fun lastLightOf(spec: SkySpec, eclipse: Eclipse, dayTime: Long): LastLight? {
            val sun = spec.bodies.getOrNull(eclipse.sun) ?: return null
            val cover = eclipse.coveredBy.firstOrNull()?.let(spec.bodies::getOrNull) ?: return null
            val sunDisc = Disc.of(sun, dayTime)
            val coverDisc = Disc.of(cover, dayTime)
            if (coverDisc.radius < sunDisc.radius) return null
            val away = Vector3f(sunDisc.direction).sub(coverDisc.direction)
            // Only the part across the sky: what points along the sun's own direction is not a way across it.
            away.sub(Vector3f(sunDisc.direction).mul(away.dot(sunDisc.direction)))
            if (away.lengthSquared() == 0.0f) return null
            away.normalize().mul(Math.tan(sunDisc.radius).toFloat())
            return LastLight(Vector3f(sunDisc.direction).add(away).normalize(), sunDisc.radius)
        }

        /** How much of a sun has to be covered before the day begins to darken. */
        private const val SHADOW_FROM = 0.5f

        /**
         * How much of an eclipse's look is in the sky with its sun at [altitudeDegrees], `0..1`: all of it while the
         * sun is up, fading as it sets, and none once it is down — past that the world outside the shadow is in night
         * too, so a moon that never leaves its sun keeps neither a violet sky nor a sunset ring burning at midnight.
         * The corona and the diamond need nothing of this, the horizon hiding them with the sun; the light the game
         * reads needs nothing either, night being as dark.
         */
        fun lookAt(altitudeDegrees: Float): Float =
            ((altitudeDegrees - LOOK_GONE_BELOW) / (LOOK_WHOLE_ABOVE - LOOK_GONE_BELOW)).coerceIn(0.0f, 1.0f)

        /** The sun's altitude, in degrees, by which an eclipse's look is gone, and above which it is whole. */
        private const val LOOK_GONE_BELOW = -4.0f
        private const val LOOK_WHOLE_ABOVE = 2.0f

        /**
         * What totality looks like: a dusky violet sky and fog, and a cold light. A sun that burns black is a
         * totality that never ends, so it takes these too, and the two agree.
         */
        val TOTALITY_SKY = Rgba(0.20f, 0.15f, 0.31f)
        val TOTALITY_FOG = Rgba(0.24f, 0.19f, 0.33f)
        val TOTALITY_LIGHT = Rgba(0.42f, 0.38f, 0.62f)

        /** Every sun some body is covering at [dayTime], in the spec's order. */
        fun of(spec: SkySpec, dayTime: Long): List<Eclipse> =
            spec.bodies.indices.mapNotNull { index -> of(spec, index, dayTime) }

        /** The body at [sunIndex] being covered at [dayTime], or null where it is not a sun or nothing covers it. */
        fun of(spec: SkySpec, sunIndex: Int, dayTime: Long): Eclipse? {
            val sun = spec.bodies.getOrNull(sunIndex) ?: return null
            if (sun.phase != null) return null
            val sunDisc = Disc.of(sun, dayTime)

            fun canCover(body: CelestialBody) =
                body.blending == Blending.COVERS && body.path.distanceAt(dayTime) < sun.path.distanceAt(dayTime)

            val hidden = spec.bodies.withIndex()
                .filter { (index, body) -> index != sunIndex && canCover(body) }
                .map { (index, body) -> index to sunDisc.shareHiddenBy(Disc.of(body, dayTime)) }
                .filter { (_, share) -> share > 0.0 }
            if (hidden.isEmpty()) return null
            // Two bodies over the same part of a disc are counted twice. Coverage only drives a fade, and a
            // sun hidden by two moons at once is rare enough to forgive.
            val coverage = hidden.sumOf { (_, share) -> share }.coerceAtMost(1.0)
            return Eclipse(sunIndex, coverage.toFloat(), hidden.map { (index, _) -> index })
        }

        /**
         * Every eclipse of every sun between [from] and [until], in the order they begin.
         *
         * Walked rather than solved, for [HorizonCrossing]'s reason, at [PASSAGE_STEP]: a graze shorter than a
         * step can be missed, and every time is good to within one. One already under way at [from] begins
         * there, and one still under way at [until] ends there.
         */
        fun passagesBetween(spec: SkySpec, from: Long, until: Long): List<EclipsePassage> {
            val suns = spec.bodies.indices.filter { spec.bodies[it].phase == null }
            // Mutable and imperative on purpose: a month of sky is tens of thousands of samples.
            val underWay = mutableMapOf<Int, PassageSoFar>()
            val finished = mutableListOf<EclipsePassage>()
            var time = from
            while (time <= until) {
                for (sun in suns) {
                    val eclipse = of(spec, sun, time)
                    val soFar = underWay[sun]
                    when {
                        eclipse != null && soFar == null -> underWay[sun] = PassageSoFar(sun, time, eclipse)
                        eclipse != null && soFar != null -> soFar.add(time, eclipse)
                        eclipse == null && soFar != null -> {
                            finished += soFar.endedAt(time - PASSAGE_STEP)
                            underWay.remove(sun)
                        }
                    }
                }
                time += PASSAGE_STEP
            }
            finished += underWay.values.map { it.endedAt(until) }
            return finished.sortedBy { it.begins }
        }

        /** Ticks between samples of [passagesBetween]: a second, and far shorter than any eclipse worth seeing. */
        const val PASSAGE_STEP = 20L

        /**
         * What share of a disc of [sunRadius] a disc of [coverRadius] hides with their centres [separation] apart,
         * all as angles in radians: the overlap of two circles, over the first one's area.
         *
         * Flat geometry on a curved sky, which is exact enough for discs a few degrees across.
         */
        internal fun overlapShare(sunRadius: Double, coverRadius: Double, separation: Double): Double {
            val apart = separation >= sunRadius + coverRadius
            val oneInsideTheOther = separation <= abs(sunRadius - coverRadius)
            val mostItCanHide = min(1.0, (coverRadius * coverRadius) / (sunRadius * sunRadius))
            return when {
                apart -> 0.0
                oneInsideTheOther -> mostItCanHide
                // Bounded because the lens rounds past containment just before containment begins.
                else -> min(mostItCanHide, lensArea(sunRadius, coverRadius, separation) / (PI * sunRadius * sunRadius))
            }
        }

        /** The area two crossing circles share: two circular segments, less the kite between their centres. */
        private fun lensArea(first: Double, second: Double, separation: Double): Double {
            val firstSegment = first * first *
                acos(((separation * separation + first * first - second * second) / (2 * separation * first)).coerceIn(-1.0, 1.0))
            val secondSegment = second * second *
                acos(((separation * separation + second * second - first * first) / (2 * separation * second)).coerceIn(-1.0, 1.0))
            val kite = 0.5 * sqrt(
                ((-separation + first + second) * (separation + first - second) *
                    (separation - first + second) * (separation + first + second)).coerceAtLeast(0.0),
            )
            return firstSegment + secondSegment - kite
        }

        /** At or past this, the disc reads as wholly hidden. Short of one only for rounding. */
        private const val TOTAL = 0.999f
    }
}

/**
 * One sun's eclipse from first contact to last.
 *
 * [totalFrom] and [totalUntil] are null where it never covered the whole disc: a partial eclipse, or an annular one,
 * whose [greatest] is as much of the sun as a smaller body can hide.
 */
data class EclipsePassage(
    val sun: Int,
    val coveredBy: List<Int>,
    val begins: Long,
    val ends: Long,
    val greatestAt: Long,
    val greatest: Float,
    val totalFrom: Long?,
    val totalUntil: Long?,
) {
    val isTotal: Boolean get() = totalFrom != null

    /** How long the disc was wholly hidden, in ticks; nought for an eclipse that never was. */
    val totalTicks: Long get() = if (totalFrom != null && totalUntil != null) totalUntil - totalFrom + 1 else 0L

    /**
     * The middle of totality, or the moment of greatest coverage where there was none. Not [greatestAt], which is
     * totality's first moment and for a slow moon minutes from its middle.
     */
    val deepestAt: Long get() = if (totalFrom != null && totalUntil != null) (totalFrom + totalUntil) / 2 else greatestAt
}

/** The last of an eclipsed sun's light: which way it lies, and the sun's angular radius in radians. */
data class LastLight(val direction: Vector3f, val sunRadius: Double)

/** A passage [Eclipse.passagesBetween] is still walking through. */
private class PassageSoFar(private val sun: Int, private val begins: Long, first: Eclipse) {
    private val coveredBy = linkedSetOf<Int>()
    private var greatestAt = begins
    private var greatest = 0.0f
    private var totalFrom: Long? = null
    private var totalUntil: Long? = null

    init {
        add(begins, first)
    }

    fun add(time: Long, eclipse: Eclipse) {
        coveredBy += eclipse.coveredBy
        if (eclipse.coverage > greatest) {
            greatest = eclipse.coverage
            greatestAt = time
        }
        if (eclipse.isTotal) {
            if (totalFrom == null) totalFrom = time
            totalUntil = time
        }
    }

    fun endedAt(time: Long) =
        EclipsePassage(sun, coveredBy.toList(), begins, time, greatestAt, greatest, totalFrom, totalUntil)
}

/** A body's disc on the sky at one instant: where it points, and its angular radius in radians. */
private class Disc(val direction: Vector3f, val radius: Double) {

    fun shareHiddenBy(cover: Disc): Double =
        Eclipse.overlapShare(radius, cover.radius, CelestialPath.angleBetween(direction, cover.direction))

    companion object {
        fun of(body: CelestialBody, dayTime: Long): Disc {
            val halfExtent = when (val look = body.appearance) {
                is Appearance.Sprite -> look.angularSize * look.discShare
            }
            return Disc(body.path.directionAt(dayTime), atan((halfExtent / body.path.distanceAt(dayTime)).toDouble()))
        }
    }
}
