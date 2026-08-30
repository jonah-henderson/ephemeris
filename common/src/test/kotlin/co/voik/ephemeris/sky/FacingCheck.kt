package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * That a sprite comes up level, stays as it came up, and never turns of its own accord.
 *
 * **The contract is deliberately not "level at every instant"** (Jonah, 2026-08-29). Upright names exactly
 * one reference — world up projected into the sprite's own plane — and that reference is undefined straight
 * overhead and reverses through it, so anything upright at every instant spins half a turn as a body crosses
 * the zenith. There is no arithmetic around that. So the square is laid on the horizon once, as the body
 * rises, and the path carries it rigidly from there.
 */
class FacingCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    /** The same walk [CelestialPath.levellingTurnOf] makes, so a check lands on the tick it chose. */
    val walkStep = day / CelestialPath.SAMPLES_AROUND

    val paths = mapOf(
        "vanilla" to Orbit.VANILLA_SUN,
        "vanilla's moon" to Orbit.VANILLA_MOON,
        "tilted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 40.0f),
        "steeply tilted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 70.0f, ascendingNodeDegrees = 30.0f),
        "over the zenith, south to north" to Orbit.VANILLA_SUN.copy(ascendingNodeDegrees = 0.0f),
        "flat and lifted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 40.0f),
        "lifted vanilla" to Orbit.VANILLA_SUN.copy(liftDegrees = 45.0f),
        "epicycling" to Motions(
            listOf(
                Motion.Turn(Direction.Axis.Y, Orbit.VANILLAS_NODE),
                Motion.Sweep(Direction.Axis.X, day, pacing = Pacing.EVEN),
                Motion.Sweep(Direction.Axis.Z, day / 3, pacing = Pacing.EVEN),
            ),
        ),
    )

    fun facedAt(path: CelestialPath, tick: Long): Quaternionf =
        Facing.LIKE_VANILLA.turn(path.orientationAt(tick), path.levellingTurn)

    fun sidewaysAt(path: CelestialPath, tick: Long): Vector3f =
        facedAt(path, tick).transform(Vector3f(1.0f, 0.0f, 0.0f))

    fun bodyAt(path: CelestialPath, tick: Long): Vector3f =
        path.orientationAt(tick).transform(Vector3f(0.0f, 1.0f, 0.0f))

    test("turning a sprite never moves the body") {
        // The one thing facing must not do: it rolls about the line of sight, so the body cannot shift.
        for ((name, path) in paths) {
            for (tick in 0..<day step 250) {
                val moved = bodyAt(path, tick.toLong())
                    .distance(facedAt(path, tick.toLong()).transform(Vector3f(0.0f, 1.0f, 0.0f)))
                check(moved < 0.001f) { "Facing '$name' at tick $tick moved the body by $moved" }
            }
        }
    }

    test("the default hands vanilla's own path straight back, untouched") {
        // The acceptance test for the whole idea: vanilla's frame already rises level, so its turn is zero
        // and the overworld's sun and moon are bit for bit what they were.
        val body = CelestialBody(
            Orbit.VANILLA_SUN,
            Appearance.Sprite(co.voik.ephemeris.Rgba.WHITE, 30.0f, Appearance.SUN_SHAPES),
        )
        check(body.facing == Facing.LIKE_VANILLA) { "The default facing is no longer vanilla's" }
        check(Orbit.VANILLA_SUN.levellingTurn == 0.0f) {
            "Vanilla's path wants a turn of ${Orbit.VANILLA_SUN.levellingTurn}, where it used to need none"
        }
        for (tick in 0..<day step 100) {
            val own = Orbit.VANILLA_SUN.orientationAt(tick.toLong())
            val turned = Facing.LIKE_VANILLA.turn(own, Orbit.VANILLA_SUN.levellingTurn)
            check(turned == own) { "The default changed vanilla's frame at tick $tick: $own became $turned" }
        }
    }

    test("every path comes up level") {
        // The spec in one line: as the body crosses the horizon climbing, the square's top and bottom edges
        // lie along it.
        for ((name, path) in paths) {
            var wasDown = path.altitudeAt(day - walkStep.toLong()) < 0.0f
            var rose: Long? = null
            for (tick in 0..<day step walkStep) {
                val altitude = path.altitudeAt(tick.toLong())
                if (wasDown && altitude >= 0.0f) {
                    rose = tick.toLong()
                    break
                }
                wasDown = altitude < 0.0f
            }
            if (rose == null) continue
            val lean = Math.abs(sidewaysAt(path, rose).y)
            check(lean < 0.01f) { "'$name' came up leaning by $lean out of the horizontal, at tick $rose" }
        }
    }

    test("and then holds that orientation the whole way round") {
        // **Locked to the path, which is what stops the spin.** The turn is one constant, so the faced
        // frame is the path's own frame rigidly rolled — the angle between them can never vary.
        for ((name, path) in paths) {
            val rolls = (0..<day step 100).map { tick ->
                val own = path.orientationAt(tick.toLong())
                val carried = Quaternionf(own).invert().mul(facedAt(path, tick.toLong()))
                // `atan2` of the vector part against the scalar, not `acos` of the scalar: the second
                // is ill-conditioned near no rotation at all, and reports hundredths of a degree of
                // float noise where a path is untouched.
                val turning = Math.sqrt((carried.x * carried.x + carried.y * carried.y + carried.z * carried.z).toDouble())
                Math.toDegrees(2.0 * Math.atan2(turning, Math.abs(carried.w).toDouble()))
            }
            val drift = rolls.max() - rolls.min()
            check(drift < 0.01) {
                "'$name' rolled its sprite by $drift° against its own path over a day, so the orientation " +
                    "is being recomputed rather than carried"
            }
        }
    }

    test("nothing jumps, anywhere, including straight overhead") {
        // **What the old rule could not do.** Levelling at every instant had to give up within a degree of
        // the zenith and reversed through it, so this check used to carve out the poles and a body crossing
        // the top spun half a turn (Jonah, walked). A constant turn on a continuous frame has nowhere to
        // give up, so nothing is exempt here now.
        val step = 20
        for ((name, path) in paths) {
            var worst = 0.0f
            var worstAt = 0L
            var previous = sidewaysAt(path, 0)
            for (tick in step..<day step step) {
                val now = sidewaysAt(path, tick.toLong())
                val moved = previous.distance(now)
                if (moved > worst) {
                    worst = moved
                    worstAt = tick.toLong()
                }
                previous = now
            }
            check(worst < 0.05f) {
                "'$name' turned its sprite by $worst in $step ticks, at tick $worstAt — near two means it " +
                    "flipped end for end, and anything sudden reads as a snap"
            }
        }
    }

    test("a path that leans is what the turn is for") {
        // Whether a lift rolls a sprite was argued both ways before being measured. It does, and a path
        // like that comes up on its side without a turn — which is the whole reason there is one.
        val lifted = Orbit.VANILLA_SUN.copy(liftDegrees = 45.0f)
        val leaning = (0..<day step 250)
            .map { Math.abs(lifted.orientationAt(it.toLong()).transform(Vector3f(1.0f, 0.0f, 0.0f)).y) }
            .max()
        check(leaning > 0.01f) {
            "A lifted path did not roll its frame (worst lean $leaning), so there is nothing to correct and " +
                "the turn is pointless"
        }
        check(lifted.levellingTurn != 0.0f) { "...yet its levelling turn is zero, so nothing corrects it" }
    }
})
