package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * That a sprite's edges lie along the horizon wherever it stands, and that following that round never snaps.
 *
 * **The contract is level at every instant, and the spare half turn is what makes it hard** (Jonah,
 * 2026-08-30). Level names the sprite only up to turning it end for end, so the upright answer taken afresh
 * each instant is level too — and snaps a body crossing the zenith, where upright stops meaning anything.
 * `CelestialPath.levellingOf` anchors at the rising and follows one answer round from there, which lets a
 * sprite go upside down rather than jump and is the least turning of any rule that stays level.
 */
class LevellingCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    val paths = mapOf(
        "vanilla" to Orbit.VANILLA_SUN,
        "vanilla's moon" to Orbit.VANILLA_MOON,
        "tilted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 40.0f),
        "steeply tilted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 70.0f, ascendingNodeDegrees = 30.0f),
        "over the zenith, south to north" to Orbit.VANILLA_SUN.copy(ascendingNodeDegrees = 0.0f),
        // The case the old rule was rejected on: near enough the zenith to turn fast, not near enough to
        // be the exactly-overhead path that needs no turning at all.
        "just under the zenith" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 2.0f),
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

    fun levelledAt(path: CelestialPath, tick: Long): Quaternionf =
        Quaternionf(path.orientationAt(tick)).rotateY(path.levellingTurnAt(tick))

    fun sidewaysAt(path: CelestialPath, tick: Long): Vector3f =
        levelledAt(path, tick).transform(Vector3f(1.0f, 0.0f, 0.0f))

    /** The rival rule: level, and choosing the upright half turn afresh every time it is asked. */
    fun uprightTurnAt(path: CelestialPath, tick: Long): Float {
        val frame = path.orientationAt(tick)
        val sideways = frame.transform(Vector3f(1.0f, 0.0f, 0.0f))
        val upward = frame.transform(Vector3f(0.0f, 0.0f, 1.0f))
        return Math.atan2(sideways.y.toDouble(), upward.y.toDouble()).toFloat()
    }

    /** How far the sprite's sideways axis swings between successive readings, worst first. */
    fun worstSwingWithStep(path: CelestialPath, step: Int): Float {
        var worst = 0.0f
        var previous = sidewaysAt(path, 0)
        for (tick in step..<day step step) {
            val now = sidewaysAt(path, tick.toLong())
            worst = Math.max(worst, previous.distance(now))
            previous = now
        }
        return worst
    }

    test("turning a sprite never moves the body") {
        // The one thing levelling must not do: it rolls about the line of sight, so the body cannot shift.
        for ((name, path) in paths) {
            for (tick in 0..<day step 250) {
                val body = path.orientationAt(tick.toLong()).transform(Vector3f(0.0f, 1.0f, 0.0f))
                val moved = body.distance(levelledAt(path, tick.toLong()).transform(Vector3f(0.0f, 1.0f, 0.0f)))
                check(moved < 0.001f) { "Levelling '$name' at tick $tick moved the body by $moved" }
            }
        }
    }

    test("every path is level at every instant") {
        // The whole specification in one line, and the fault it was written for: a square sun rising level
        // in the north set forty degrees over in the southeast, because the turn was one constant per path
        // rather than one per moment (Jonah, 2026-08-30, walked).
        for ((name, path) in paths) {
            var worst = 0.0f
            var worstAt = 0L
            for (tick in 0..<day step 7) {
                val lean = Math.abs(sidewaysAt(path, tick.toLong()).y)
                if (lean > worst) {
                    worst = lean
                    worstAt = tick.toLong()
                }
            }
            // Read between samples of the walk, so a hair of interpolation error is expected and a sprite
            // visibly off the horizontal is not.
            check(worst < 0.02f) { "'$name' leaned by $worst out of the horizontal, at tick $worstAt" }
        }
    }

    test("vanilla's own path is handed back untouched") {
        // The acceptance test for the whole idea: vanilla's frame is already level at every tick, so it
        // wants no roll at any of them and the overworld is what it was.
        for (path in listOf(Orbit.VANILLA_SUN, Orbit.VANILLA_MOON)) {
            for (tick in 0..<day step 100) {
                val turn = Math.toDegrees(path.levellingTurnAt(tick.toLong()).toDouble())
                check(Math.abs(turn) < 0.01) { "Vanilla's path wanted a roll of $turn° at tick $tick" }
            }
        }
    }

    test("nothing snaps, anywhere, including straight overhead") {
        // **A fast turn and a snap are told apart by halving the step.** A body skimming the zenith really
        // does swing through half a turn quickly, and no rule that stays level can spare it that; what the
        // old rule did there was discontinuous, which is a different thing. Sampled twice as finely, a
        // continuous swing halves and a snap does not move at all.
        for ((name, path) in paths) {
            val coarse = worstSwingWithStep(path, 20)
            val fine = worstSwingWithStep(path, 10)
            check(fine < coarse * 0.75f + 0.001f) {
                "'$name' swung by $coarse in 20 ticks and still $fine in 10, so it is a jump rather than a turn"
            }
            // Two is end for end. Nothing continuous reaches it, so this only catches an outright flip that
            // happens to land between two samples and so escapes the halving.
            check(coarse < 1.9f) { "'$name' turned its sprite by $coarse in 20 ticks, which is a flip" }
        }
    }

    test("following one answer round is the least turning there is") {
        // The reason this rule and not the other: both are level, and this one is the one that moves least.
        fun turningOf(turnAt: (Long) -> Float): Double {
            var total = 0.0
            var previous = turnAt(0)
            for (tick in 10..<day step 10) {
                val now = turnAt(tick.toLong())
                var change = (now - previous).toDouble()
                while (change > Math.PI) change -= Math.PI * 2
                while (change < -Math.PI) change += Math.PI * 2
                total += Math.abs(change)
                previous = now
            }
            return Math.toDegrees(total)
        }
        for ((name, path) in paths) {
            val followed = turningOf { tick -> path.levellingTurnAt(tick) }
            val afresh = turningOf { tick -> uprightTurnAt(path, tick) }
            check(followed <= afresh + 1.0) {
                "'$name' turned $followed° followed round against $afresh° chosen afresh, so following is " +
                    "not the cheaper rule after all"
            }
        }
    }

    test("a path that leans is rolled, and by a differing amount") {
        // Whether a lift rolls a sprite was argued both ways before being measured. It does — and the roll
        // that corrects it varies over the day, which is exactly what one constant could not express.
        val lifted = Orbit.VANILLA_SUN.copy(liftDegrees = 45.0f)
        val leaning = (0..<day step 250)
            .map { Math.abs(lifted.orientationAt(it.toLong()).transform(Vector3f(1.0f, 0.0f, 0.0f)).y) }
            .max()
        check(leaning > 0.01f) {
            "A lifted path did not roll its frame (worst lean $leaning), so there is nothing to correct"
        }
        val rolls = (0..<day step 100).map { lifted.levellingTurnAt(it.toLong()) }
        val spread = Math.toDegrees((rolls.max() - rolls.min()).toDouble())
        check(spread > 1.0) { "...yet one constant of $spread° would have levelled it all day" }
    }
})
