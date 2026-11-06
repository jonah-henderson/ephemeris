package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec
import kotlin.math.PI
import kotlin.math.abs

class StarWheelCheck : FunSpec({

    /** Vanilla's angle, which turns once a day with the sun. */
    fun vanillas(clockTime: Long) = ((clockTime % 24_000L) / 24_000.0 * 2.0 * PI).toFloat()

    /** How far apart two angles are, the short way round. */
    fun apart(one: Float, other: Float): Double {
        val raw = abs(one - other) % (2.0 * PI)
        return minOf(raw, 2.0 * PI - raw)
    }

    test("at vanilla's pace the field stands where vanilla's does, and held it stands still all day") {
        check(StarWheel.angleAt(1.234f, 1.0f, 99_999L) == 1.234f) { "vanilla's pace moved the field" }
        val held = (0L..48_000L step 6_000L).map { StarWheel.angleAt(vanillas(it), 0.0f, it) }.distinct()
        check(held.size == 1) { "a held field moved: $held" }
    }

    test("at a pace no whole number, the field still runs on smoothly across the day's wrap") {
        for (pace in listOf(0.5f, 2.5f, 0.25f)) {
            val before = StarWheel.angleAt(vanillas(23_999L), pace, 23_999L)
            val after = StarWheel.angleAt(vanillas(24_000L), pace, 24_000L)
            check(apart(before, after) < 0.01) { "at pace $pace the field jumped ${apart(before, after)} at dawn" }
        }
    }

    test("a field at half pace has turned half way round when vanilla's has turned once") {
        val start = StarWheel.angleAt(vanillas(0L), 0.5f, 0L)
        val aDayOn = StarWheel.angleAt(vanillas(24_000L), 0.5f, 24_000L)
        check(abs(apart(start, aDayOn) - PI) < 0.01) { "half pace turned ${apart(start, aDayOn)} in a day" }
    }
})
