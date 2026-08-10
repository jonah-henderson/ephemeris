package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec

/**
 * That a glow lands where the sun is.
 *
 * **This is the check that was missing**, and its absence cost a walk: the fan was aimed by the bearing
 * directly, which put every sunset exactly opposite the sun that cast it. Standing the fan upright carries
 * its bright centre to the far side, so the turn is a half turn from the bearing rather than the bearing —
 * a fact invisible in the drawing code and plain the moment anything reads the position back.
 *
 * It reads back by doing to a point what the canvas does to the mesh, so it is checking the same arithmetic
 * rather than a restatement of it.
 */
class HorizonFanCheck : FunSpec({

    test("a glow aimed at a bearing lands on that bearing") {
        for (bearing in 0..350 step 10) {
            val landed = HorizonFan.landsAt(HorizonFan.turnFor(bearing.toFloat()))
            val off = Math.abs(((landed - bearing + 540.0f) % 360.0f) - 180.0f)
            check(off < 0.01f) {
                "A glow aimed due $bearing° landed at $landed°, which is $off° out. 180 means the fan is " +
                    "being aimed by the bearing instead of by `turnFor`, and every sunset is opposite its sun"
            }
        }
    }

    test("vanilla's own two turns are east and west, the only ones it can produce") {
        // Vanilla's `(sin(sunAngle) < 0 ? 180 : 0) + 90` yields exactly these two, and they must be the
        // compass points its sun actually rises and sets at — which is what makes ours a generalisation of
        // vanilla's rather than a different scheme that happens to agree somewhere.
        check(Math.abs(HorizonFan.landsAt(270.0f) - 90.0f) < 0.01f) {
            "Vanilla's other turn did not land due east, but at ${HorizonFan.landsAt(270.0f)}°"
        }
        check(Math.abs(HorizonFan.landsAt(90.0f) - 270.0f) < 0.01f) {
            "Vanilla's turn did not land due west, but at ${HorizonFan.landsAt(90.0f)}°"
        }
    }

    test("a sun's glow follows it around the compass") {
        // End to end: take a real path, ask where the sun is, aim a glow, and check the two agree.
        val tilted = Orbit.VANILLA_SUN.copy(inclinationDegrees = 40.0f, ascendingNodeDegrees = 30.0f)
        for (tick in 0..<Orbit.TICKS_PER_VANILLA_DAY step 500) {
            if (Math.abs(tilted.altitudeAt(tick.toLong())) > 80.0f) continue
            val where = tilted.bearingAt(tick.toLong())
            val landed = HorizonFan.landsAt(HorizonFan.turnFor(where))
            val off = Math.abs(((landed - where + 540.0f) % 360.0f) - 180.0f)
            check(off < 0.01f) { "At tick $tick the sun was at $where° and its glow at $landed°" }
        }
    }
})
