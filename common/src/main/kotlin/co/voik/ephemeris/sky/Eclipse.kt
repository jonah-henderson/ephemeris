package co.voik.ephemeris.sky

import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
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

    companion object {
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
}

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

    fun shareHiddenBy(cover: Disc): Double = Eclipse.overlapShare(radius, cover.radius, angleTo(cover))

    /** The angle between two directions, by `atan2` rather than `acos`, which loses everything near nought. */
    private fun angleTo(other: Disc): Double {
        val across = Vector3f(direction).cross(other.direction).length().toDouble()
        val along = direction.dot(other.direction).toDouble()
        return atan2(across, along)
    }

    companion object {
        fun of(body: CelestialBody, dayTime: Long): Disc {
            val halfExtent = when (val look = body.appearance) {
                is Appearance.Sprite -> look.angularSize * look.discShare
            }
            return Disc(body.path.directionAt(dayTime), atan((halfExtent / body.path.distanceAt(dayTime)).toDouble()))
        }
    }
}
