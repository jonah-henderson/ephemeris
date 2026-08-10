package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec
import org.joml.Vector3f

/**
 * That levelling a sprite leaves the body where it was, and that vanilla's own bodies come out unchanged.
 *
 * The second is the acceptance test for the whole idea: if [Facing.LIKE_VANILLA] does not hand back vanilla's
 * own frame on vanilla's path, then the default has quietly changed how the overworld's sun and moon look.
 */
class FacingCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    /**
     * How near a pole a body may be before facing stops having an answer for it.
     *
     * Nothing is square to straight-up, so a levelled frame hands back to the path's own there and the two
     * differ. Two degrees of sky, directly overhead and directly underfoot.
     */
    val VISIBLE_TO = 88.0f

    fun bodyDirection(path: CelestialPath, dayTime: Long): Vector3f =
        path.orientationAt(dayTime).transform(Vector3f(0.0f, 1.0f, 0.0f))

    fun levelledDirection(path: CelestialPath, dayTime: Long): Vector3f =
        Facing.levelled(path.orientationAt(dayTime)).transform(Vector3f(0.0f, 1.0f, 0.0f))

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
                val moved = bodyDirection(path, tick.toLong()).distance(levelledDirection(path, tick.toLong()))
                check(moved < 0.001f) { "Levelling $path at tick $tick moved the body by $moved" }
            }
        }
    }

    test("the default hands vanilla's own path straight back, untouched") {
        // The acceptance test: on vanilla's path the default must return the *same* frame, not one equal to
        // it up to a mirror. Vanilla sweeps about its quad's local X and that axis is already horizontal, so
        // there is nothing to level and levelling it would only risk disagreeing about which way round.
        val body = CelestialBody(
            Orbit.VANILLA_SUN,
            Appearance.Sprite(co.voik.ephemeris.Rgba.WHITE, 30.0f, Appearance.SUN_SHAPES),
        )
        check(body.facing == Facing.LIKE_VANILLA) { "The default facing is no longer vanilla's" }
        check(Orbit.VANILLA_SUN.framesAreLevel) { "Vanilla's own path no longer reads as level" }
        for (tick in 0..<day step 100) {
            val own = Orbit.VANILLA_SUN.orientationAt(tick.toLong())
            val turned = Facing.LIKE_VANILLA.turn(own, Orbit.VANILLA_SUN.framesAreLevel)
            check(turned == own) { "The default changed vanilla's frame at tick $tick: $own became $turned" }
        }
    }

    test("the default keeps every sprite level, on every path") {
        // The other half of the bargain, and the one a walk noticed missing: a tilted orbit held its frame
        // fixed in *world* space, which reads as the sprite slowly turning as the body crosses. Level means
        // level from wherever you are watching.
        val paths = mapOf(
            "vanilla" to Orbit.VANILLA_SUN,
            "tilted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 40.0f),
            "steeply tilted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 70.0f, ascendingNodeDegrees = 30.0f),
            "flat and lifted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 40.0f),
            "lifted vanilla" to Orbit.VANILLA_SUN.copy(liftDegrees = 45.0f),
        )
        for ((name, path) in paths) {
            val worst = (0..<day step 100)
                .filter { Math.abs(path.altitudeAt(it.toLong())) < VISIBLE_TO }
                .map {
                    val turned = Facing.LIKE_VANILLA.turn(path.orientationAt(it.toLong()), path.framesAreLevel)
                    Math.abs(turned.transform(Vector3f(1.0f, 0.0f, 0.0f)).y)
                }
                .max()
            check(worst < 0.01f) {
                "'$name' leaned its sprite by $worst out of the horizontal, so it turns as it crosses the sky"
            }
        }
    }

    test("levelling really does level, where the path alone would lean") {
        val tilted = Orbit.VANILLA_SUN.copy(inclinationDegrees = 55.0f, ascendingNodeDegrees = 20.0f)

        fun rightwardLean(atTick: Long, levelled: Boolean): Float {
            val frame = tilted.orientationAt(atTick)
            val turned = if (levelled) Facing.levelled(frame) else frame
            return turned.transform(Vector3f(1.0f, 0.0f, 0.0f)).y
        }

        val held = (0..<day step 250).map { Math.abs(rightwardLean(it.toLong(), levelled = true)) }.max()
        check(held < 0.01f) { "A levelled sprite leaned by $held, so its horns are not flat" }

        val leaning = (0..<day step 250).map { Math.abs(rightwardLean(it.toLong(), levelled = false)) }.max()
        check(leaning > 0.1f) {
            "A tilted path never leaned its frame at all (worst $leaning), so there is nothing for `LEVEL` " +
                "to correct and the option is pointless"
        }
    }

    test("the default never flips a sprite, on any path") {
        // **Walked and found.** A moon on a flat polar path flickered between crescent-left and
        // crescent-right every frame. The default used to level the sprite and then agree its *sign* with
        // the path's own frame; that agreement is `-sin(sweep) * cos(inclination)`, which is identically
        // zero for a flat path — so the sign came down to which way floating point rounded, and changed
        // constantly. It is also zero at the top and bottom of *any* arc, so every path flipped once a turn.
        //
        // Levelling replaced it, and levelling has exactly one place it cannot answer: a body straight
        // overhead or straight underfoot, where no horizontal direction is square to it. That is geometry
        // rather than a defect — so this asks about the sky a player can see, which is where a flip would
        // actually be watched. A circle reaching the zenith is vanilla's own case and keeps its frame; the
        // nadir is below the world.
        val paths = mapOf(
            "vanilla" to Orbit.VANILLA_SUN,
            "tilted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 40.0f),
            "flat and lifted" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 40.0f),
            "flat and low" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 90.0f, liftDegrees = 10.0f),
            "nearly flat" to Orbit.VANILLA_SUN.copy(inclinationDegrees = 87.0f, liftDegrees = 30.0f),
            "epicycling" to Motions(
                listOf(
                    Motion.Turn(net.minecraft.core.Direction.Axis.Y, Orbit.VANILLAS_NODE),
                    Motion.Sweep(net.minecraft.core.Direction.Axis.X, day, pacing = Pacing.EVEN),
                    Motion.Sweep(net.minecraft.core.Direction.Axis.Z, day / 3, pacing = Pacing.EVEN),
                ),
            ),
        )
        val step = 20

        for ((name, path) in paths) {
            fun rightwardAt(tick: Long): Vector3f =
                Facing.LIKE_VANILLA.turn(path.orientationAt(tick), path.framesAreLevel).transform(Vector3f(1.0f, 0.0f, 0.0f))

            fun isOverhead(tick: Long) = Math.abs(path.altitudeAt(tick)) > VISIBLE_TO

            var worst = 0.0f
            var worstAt = 0L
            var previous = rightwardAt(0)
            for (tick in step..<day step step) {
                val now = rightwardAt(tick.toLong())
                val moved = previous.distance(now)
                if (moved > worst && !isOverhead(tick.toLong()) && !isOverhead((tick - step).toLong())) {
                    worst = moved
                    worstAt = tick.toLong()
                }
                previous = now
            }
            // A flip is a jump of about two — the axis reversing outright. Ordinary travel moves it a little.
            check(worst < 0.5f) {
                "'$name' turned its sprite by $worst in $step ticks, at tick $worstAt. Near two means the " +
                    "sprite flipped end for end, which reads as a crescent snapping to the other side"
            }
        }
    }

    test("the pole is the only place levelling gives up, and it is out of sight") {
        // The exemption above is only honest if it is *narrow*. A body within a degree of straight up or
        // straight down is the whole of it, and that is a sprite the size of the sky's centre point.
        val steep = Motions(
            listOf(
                Motion.Turn(net.minecraft.core.Direction.Axis.Y, Orbit.VANILLAS_NODE),
                Motion.Sweep(net.minecraft.core.Direction.Axis.X, day, pacing = Pacing.EVEN),
                Motion.Sweep(net.minecraft.core.Direction.Axis.Z, day / 3, pacing = Pacing.EVEN),
            ),
        )
        val exempt = (0..<day step 20).count { Math.abs(steep.altitudeAt(it.toLong())) > VISIBLE_TO }
        val ticks = (0..<day step 20).count()
        check(exempt * 100 / ticks < 2) {
            "$exempt of $ticks sampled ticks sit within ${90.0f - VISIBLE_TO}° of a pole, which is too much " +
                "of the day to be waving through as unwatchable"
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
