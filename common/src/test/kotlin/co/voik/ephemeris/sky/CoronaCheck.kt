package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** What a [Corona] says its streamers are at a moment: how strong, how long and where round the body. */
class CoronaCheck : FunSpec({

    fun corona(shimmer: Float = 0.0f, turnsPerDay: Float = 0.0f) =
        Corona(rays = RAYS, reach = REACH, colour = Rgba.WHITE, turnsPerDay = turnsPerDay, shimmer = shimmer)

    test("with no shimmer it never pulses") {
        val steady = (0L..A_DAY step A_MOMENT).map(corona()::strengthAt)
        check(steady.all { it == 1.0f }) { "a corona with no shimmer pulsed to ${steady.min()}" }
    }

    test("a shimmer pulses between its depth and full strength") {
        val pulsing = (0L..A_DAY step A_MOMENT).map(corona(shimmer = 0.6f)::strengthAt)
        check(pulsing.all { it in 0.4f - CLOSE..1.0f + CLOSE }) { "it pulsed out to ${pulsing.min()}..${pulsing.max()}" }
        check(pulsing.max() - pulsing.min() > 0.5f) { "a deep shimmer barely moved: ${pulsing.min()}..${pulsing.max()}" }
    }

    test("every streamer is there, no longer than the reach and no shorter than its shortest share") {
        val rays = corona().raysAt(0L)
        check(rays.size == RAYS) { "${rays.size} rays for $RAYS" }
        check(rays.all { it.length in REACH * 0.45f - CLOSE..REACH + CLOSE }) { "lengths ran ${rays.map { it.length }}" }
        check(rays.map { it.length }.distinct().size > RAYS / 2) { "the rays were nearly all one length" }
    }

    test("the ring turns as often a day as it says, and keeps its shape") {
        val turning = corona(turnsPerDay = 2.0f)
        val atDawn = turning.raysAt(0L).first()
        val quarterDay = turning.raysAt(A_DAY / 4).first()
        check(abs(quarterDay.turn - (atDawn.turn + 0.5f) % 1.0f) < CLOSE) { "a quarter-day turned it to ${quarterDay.turn}" }
        check(quarterDay.length == atDawn.length) { "a ray changed its length as it turned" }
    }
})

private const val RAYS = 24
private const val REACH = 3.0f
private const val A_DAY = 24_000L
private const val A_MOMENT = 20L
private const val CLOSE = 0.001f
