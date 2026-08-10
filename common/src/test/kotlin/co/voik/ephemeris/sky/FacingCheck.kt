package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec
import org.joml.Vector3f

/**
 * That standing a sprite upright leaves it where it was, and that vanilla's own bodies come out unchanged.
 *
 * The second is the acceptance test for the whole idea: if [Facing.upright] does not reproduce vanilla's
 * frame on vanilla's path, then the default has quietly changed how the overworld's sun and moon look.
 */
class FacingCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    fun bodyDirection(path: CelestialPath, dayTime: Long): Vector3f =
        path.orientationAt(dayTime).transform(Vector3f(0.0f, 1.0f, 0.0f))

    fun uprightDirection(path: CelestialPath, dayTime: Long): Vector3f =
        Facing.upright(path.orientationAt(dayTime)).transform(Vector3f(0.0f, 1.0f, 0.0f))

    test("standing a sprite upright never moves the body") {
        // The one thing facing must not do. Every path, every hour.
        val paths = listOf(
            Orbit.VANILLA_SUN,
            Orbit.VANILLA_SUN.copy(inclinationDegrees = 40.0f, ascendingNodeDegrees = 25.0f),
            Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 30.0f),
            Orbit.risingAt(200.0f),
        )
        for (path in paths) {
            for (tick in 0..<day step 250) {
                val moved = bodyDirection(path, tick.toLong()).distance(uprightDirection(path, tick.toLong()))
                check(moved < 0.001f) { "Standing $path upright at tick $tick moved the body by $moved" }
            }
        }
    }

    test("vanilla's own sun comes out exactly as vanilla draws it") {
        // Vanilla sweeps about the quad's own local X, so that axis never moves. If upright reproduces the
        // whole frame, the default has changed nothing about the sky everyone already knows.
        for (tick in 0..<day step 100) {
            val vanillas = Orbit.VANILLA_SUN.orientationAt(tick.toLong())
            val stood = Facing.upright(vanillas)
            // Overhead a body has no horizontal square to it and the frame is left alone by design.
            if (Math.abs(Orbit.VANILLA_SUN.altitudeAt(tick.toLong())) > 88.0f) continue

            for (axis in listOf(Vector3f(1.0f, 0.0f, 0.0f), Vector3f(0.0f, 1.0f, 0.0f), Vector3f(0.0f, 0.0f, 1.0f))) {
                val fromVanilla = vanillas.transform(Vector3f(axis))
                val fromUpright = stood.transform(Vector3f(axis))
                check(fromVanilla.distance(fromUpright) < 0.01f) {
                    "At tick $tick vanilla's frame put $axis at $fromVanilla and standing it upright put it " +
                        "at $fromUpright — the default facing is not vanilla's"
                }
            }
        }
    }

    test("a sprite held upright keeps its horns level, where the path would roll them") {
        // What the option is *for*. A tilted path rolls its frame as it travels; upright does not, which is
        // the difference between a crescent that stays a crescent and one that turns over during the night.
        val tilted = Orbit.VANILLA_SUN.copy(inclinationDegrees = 55.0f, ascendingNodeDegrees = 20.0f)

        fun rightwardTilt(atTick: Long, upright: Boolean): Float {
            val frame = tilted.orientationAt(atTick)
            val turned = if (upright) Facing.upright(frame) else frame
            return turned.transform(Vector3f(1.0f, 0.0f, 0.0f)).y
        }

        val heldLevel = (0..<day step 250).map { Math.abs(rightwardTilt(it.toLong(), upright = true)) }.max()
        check(heldLevel < 0.01f) {
            "A sprite held upright leaned by $heldLevel, so its horns are not level"
        }

        val rolledByThePath = (0..<day step 250).map { Math.abs(rightwardTilt(it.toLong(), upright = false)) }.max()
        check(rolledByThePath > 0.1f) {
            "A tilted path never rolled its frame at all (worst lean $rolledByThePath), so there is nothing " +
                "for `LIKE_VANILLA` to correct and the whole option is pointless"
        }
    }

    test("a lifted path is the case that needed it") {
        // Whether the lift rolls a sprite was argued both ways before being measured. It does, and this is
        // the number: a moon on a lifted path had visibly tilted horns before facing existed.
        val lifted = Orbit.VANILLA_SUN.copy(liftDegrees = 45.0f)
        val leaning = (0..<day step 250)
            .map { Math.abs(lifted.orientationAt(it.toLong()).transform(Vector3f(1.0f, 0.0f, 0.0f)).y) }
            .max()
        check(leaning > 0.01f) {
            "A lifted path did not roll its frame (worst lean $leaning), so the note about tilted horns is " +
                "wrong and should be struck rather than left to mislead"
        }
    }
})
