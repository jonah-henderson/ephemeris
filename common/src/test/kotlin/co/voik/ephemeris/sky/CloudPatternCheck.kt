package co.voik.ephemeris.sky

import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

class CloudPatternCheck : FunSpec({

    val side = CloudPattern.SIDE

    fun shareOf(cells: BooleanArray) = cells.count { it }.toFloat() / cells.size

    /** How many separate clumps of cloud there are, the picture wrapping at its edges. */
    fun clumpsIn(cells: BooleanArray): Int {
        val seen = BooleanArray(cells.size)
        var clumps = 0
        for (start in cells.indices) {
            if (!cells[start] || seen[start]) continue
            clumps++
            val waiting = ArrayDeque(listOf(start))
            seen[start] = true
            while (waiting.isNotEmpty()) {
                val at = waiting.removeFirst()
                val x = at % side
                val z = at / side
                for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val next = Math.floorMod(x + dx, side) + Math.floorMod(z + dz, side) * side
                    if (cells[next] && !seen[next]) {
                        seen[next] = true
                        waiting.addLast(next)
                    }
                }
            }
        }
        return clumps
    }

    /** The mean length of the runs of cloud along one axis. */
    fun meanRun(cells: BooleanArray, alongTheDrift: Boolean): Double {
        val runs = mutableListOf<Int>()
        for (line in 0..<side) {
            var run = 0
            for (step in 0..<side) {
                val at = if (alongTheDrift) step + line * side else line + step * side
                if (cells[at]) run++ else if (run > 0) { runs += run; run = 0 }
            }
            if (run > 0) runs += run
        }
        return runs.average()
    }

    test("the picture is exactly as much cloud as asked for") {
        for (cover in listOf(0.05f, 0.276f, 0.6f, 0.95f)) {
            val share = shareOf(CloudPattern(cover = cover, seed = 3L).cells())
            check(abs(share - cover) < 0.002f) { "asked for $cover, made $share" }
        }
    }

    test("broad patches are fewer clumps than small bits at the same cover") {
        val bits = clumpsIn(CloudPattern(cover = 0.3f, clumping = -1.0f, seed = 5L).cells())
        val patches = clumpsIn(CloudPattern(cover = 0.3f, clumping = 1.0f, seed = 5L).cells())
        check(patches * 3 < bits) { "small bits made $bits clumps and broad patches $patches" }
    }

    test("torn clouds stream out along the drift") {
        val torn = CloudPattern(cover = 0.3f, torn = true, seed = 9L).cells()
        val along = meanRun(torn, alongTheDrift = true)
        val across = meanRun(torn, alongTheDrift = false)
        check(along > across * 2) { "torn runs were $along along the drift and $across across it" }
    }

    test("the same pattern makes the same picture, and its size and pace are not part of it") {
        val pattern = CloudPattern(cover = 0.4f, seed = 11L)
        check(pattern.cells().contentEquals(pattern.copy(cellScale = 3.0f).cells())) { "the size changed the picture" }
        check(!CloudPattern(cellScale = 2.0f).drawsItsOwnPicture) { "a size alone asked for a picture of its own" }
    }

    test("a pattern survives the trip to a client") {
        val pattern = CloudPattern(cover = 0.7f, clumping = -0.5f, torn = true, cellScale = 2.0f, seed = 13L)
        val arrived = CloudPattern.CODEC.parse(JsonOps.INSTANCE, CloudPattern.CODEC.encodeStart(JsonOps.INSTANCE, pattern).getOrThrow())
            .getOrThrow()
        check(arrived == pattern) { "it arrived as $arrived" }
    }
})
