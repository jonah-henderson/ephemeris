package co.voik.ephemeris.sky

import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * A path rising over one horizon and setting under another: it comes up and goes down where it was asked, keeps the
 * ordinary day and night, flattens to the ordinary path where the two are opposite and to a line through the zenith
 * where they are one, and between those peaks short of overhead.
 */
class EllipseCheck : FunSpec({

    fun ellipse(rising: Float, setting: Float) =
        Ellipse(rising, setting, Orbit.TICKS_PER_VANILLA_DAY, phaseDegrees = 0.0f, distance = Orbit.VANILLA_SUN.distance)

    fun bearingAtProgress(path: Ellipse, progress: Float) = CelestialPath.bearingOf(path.directionAtProgress(progress))

    fun altitudeAtProgress(path: Ellipse, progress: Float) = CelestialPath.altitudeOf(path.directionAtProgress(progress))

    fun nearlyTheSameBearing(first: Float, second: Float) = abs(((first - second) % 360.0f + 540.0f) % 360.0f - 180.0f) < 0.5f

    test("it comes up where it rises and goes down where it sets") {
        for ((rising, setting) in listOf(0.0f to 90.0f, 0.0f to 0.0f, 45.0f to 270.0f, 90.0f to 270.0f, 180.0f to 135.0f)) {
            val path = ellipse(rising, setting)
            val cameUp = bearingAtProgress(path, RISES)
            val wentDown = bearingAtProgress(path, SETS)
            check(nearlyTheSameBearing(cameUp, rising)) { "rising at $rising, setting at $setting: it came up at $cameUp" }
            check(nearlyTheSameBearing(wentDown, setting)) { "rising at $rising, setting at $setting: it went down at $wentDown" }
        }
    }

    test("day and night are each half the way round, whatever the two points") {
        for ((rising, setting) in listOf(0.0f to 90.0f, 0.0f to 0.0f, 0.0f to 45.0f, 90.0f to 270.0f)) {
            val path = ellipse(rising, setting)
            val up = (0..<AROUND).count { altitudeAtProgress(path, it.toFloat() / AROUND) > 0.0f } / AROUND.toDouble()
            check(abs(up - 0.5) < 0.01) { "rising at $rising, setting at $setting: up for $up of the way round" }
        }
    }

    test("opposite points are the ordinary path through the zenith") {
        val path = ellipse(90.0f, 270.0f)
        for (tick in 0L..<Orbit.TICKS_PER_VANILLA_DAY step 250L) {
            val ours = path.directionAt(tick)
            val vanillas = Orbit.VANILLA_SUN.directionAt(tick)
            check(CelestialPath.angleBetween(ours, vanillas) < CLOSE) { "at $tick it stood ${Math.toDegrees(CelestialPath.angleBetween(ours, vanillas))}° off vanilla's sun" }
        }
    }

    test("one point twice climbs to overhead at noon and reaches underfoot at midnight") {
        val path = ellipse(0.0f, 0.0f)
        check(altitudeAtProgress(path, NOON) > 89.0f) { "it stood ${altitudeAtProgress(path, NOON)}° up at noon" }
        check(altitudeAtProgress(path, MIDNIGHT) < -89.0f) { "it stood ${altitudeAtProgress(path, MIDNIGHT)}° at midnight" }
        val beforeNoon = bearingAtProgress(path, 0.9f)
        val afterNoon = bearingAtProgress(path, 0.1f)
        check(nearlyTheSameBearing(beforeNoon, 0.0f) && nearlyTheSameBearing(afterNoon, 0.0f)) {
            "it left the northern sky: $beforeNoon before noon, $afterNoon after"
        }
    }

    test("between, the peak falls short of overhead and the low point of underfoot") {
        for ((rising, setting) in listOf(0.0f to 90.0f, 0.0f to 45.0f, 0.0f to 135.0f)) {
            val path = ellipse(rising, setting)
            val highest = (0..<AROUND).maxOf { altitudeAtProgress(path, it.toFloat() / AROUND) }
            val lowest = (0..<AROUND).minOf { altitudeAtProgress(path, it.toFloat() / AROUND) }
            check(highest < 88.0f && highest > 20.0f) { "rising at $rising, setting at $setting: it peaked at $highest°" }
            check(abs(lowest + highest) < 0.5f) { "rising at $rising, setting at $setting: its night was no mirror of its day" }
        }
    }

    test("it moves at an even pace along its way, never jumping") {
        val path = ellipse(0.0f, 90.0f)
        val steps = (0..<AROUND).map { at ->
            val here = path.directionAtProgress(at.toFloat() / AROUND)
            val next = path.directionAtProgress((at + 1).toFloat() / AROUND)
            CelestialPath.angleBetween(here, next)
        }
        check(steps.max() < steps.average() * 1.05) { "a step of ${steps.max()} against an average of ${steps.average()}" }
    }

    test("it is written down and read back as itself") {
        val path = ellipse(0.0f, 90.0f).copy(phaseDegrees = 180.0f)
        val written = CelestialPath.CODEC.encodeStart(JsonOps.INSTANCE, path).getOrThrow()
        val read = CelestialPath.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
        check(read == path) { "wrote $written, read back $read" }
    }
})

private const val AROUND = 2000
private const val NOON = 0.0f
private const val RISES = 0.75f
private const val SETS = 0.25f
private const val MIDNIGHT = 0.5f

/** A hundredth of a degree, as near as two paths built two ways can be asked to agree. */
private const val CLOSE = 0.0002
