package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import org.joml.Quaternionf

/**
 * One turn in a path — **the piece everything else is built out of**.
 *
 * A body's path is a stack of these applied in order, and the whole of what makes that worth doing is that
 * an ordinary circle already *is* one. Vanilla's sun is `R_y(-90°) · R_x(t)`: a fixed turn to choose which
 * way the plane leans, then a sweep. Writing it that way rather than as four named angles is what lets an
 * epicycle be two sweeps, a wobble be a sweep and an oscillation, and a figure of eight be two oscillations
 * at a two-to-one ratio — none of which needs a new case, a new codec, or a line of new maths.
 *
 * **Order is the outer frame first.** Each motion turns the frame the ones after it act in, exactly as
 * [Orbit] composes its node, its inclination, its sweep and its lift.
 */
sealed interface Motion {

    /** [frame] turned by this motion at [dayTime]. Mutates and returns it, as JOML does. */
    fun turn(frame: Quaternionf, dayTime: Long): Quaternionf

    /** How long before this motion is back where it started, or **null** for one that never moves. */
    val periodTicks: Int?

    /** The codec dispatch key. A new motion is a key and a [MapCodec], nothing more. */
    val kindKey: String

    /**
     * A fixed turn, which never moves.
     *
     * Two of these are what aim a circle: one about the vertical to choose which compass direction its
     * plane leans toward, one to tip it off the zenith.
     */
    data class Turn(val axis: Direction.Axis, val degrees: Float) : Motion {
        override val periodTicks: Int? get() = null
        override val kindKey: String get() = TURN

        override fun turn(frame: Quaternionf, dayTime: Long): Quaternionf =
            frame.turnedAbout(axis, degrees)

        companion object {
            val MAP_CODEC: MapCodec<Turn> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Direction.Axis.CODEC.fieldOf("axis").forGetter(Turn::axis),
                    Codec.FLOAT.fieldOf("degrees").forGetter(Turn::degrees),
                ).apply(instance, ::Turn)
            }
        }
    }

    /**
     * A turn that goes all the way round, over and over — what carries a body along its path.
     *
     * [pacing] is why this is not simply an angle: vanilla's sun does not travel at a constant rate, and a
     * body meant to *be* vanilla's sun has to linger near the horizon the way vanilla's does.
     */
    data class Sweep(
        val axis: Direction.Axis,
        val period: Int,
        val phaseDegrees: Float = 0.0f,
        val retrograde: Boolean = false,
        val pacing: Pacing = Pacing.VANILLAS,
    ) : Motion {
        override val periodTicks: Int get() = period
        override val kindKey: String get() = SWEEP

        override fun turn(frame: Quaternionf, dayTime: Long): Quaternionf =
            frame.turnedAbout(axis, progressAt(dayTime) * DEGREES_PER_TURN)

        /**
         * How far round, in `0.0..1.0`.
         *
         * **Steps once per tick, and must never add a partial tick.** That fraction resets to zero at every
         * tick boundary, so if the value it is added to has not incremented in the same instant, each
         * boundary is a step *backwards* — a sawtooth rather than a smooth arc. A level on
         * `DerivedLevelData` takes its day time from the overworld and the server syncs it only every 20
         * ticks, which makes that very likely. One tick is a hundredth of a degree anyway.
         */
        fun progressAt(dayTime: Long): Float {
            val revolutions = dayTime.toDouble() / period
            val travelled = if (retrograde) -revolutions else revolutions
            val raw = Mth.frac(travelled + phaseDegrees / DEGREES_PER_TURN - QUARTER_TURN)
            return pacing.applyTo(raw).toFloat()
        }

        companion object {
            val MAP_CODEC: MapCodec<Sweep> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Direction.Axis.CODEC.fieldOf("axis").forGetter(Sweep::axis),
                    Codec.INT.fieldOf("period").forGetter(Sweep::period),
                    Codec.FLOAT.optionalFieldOf("phase", 0.0f).forGetter(Sweep::phaseDegrees),
                    Codec.BOOL.optionalFieldOf("retrograde", false).forGetter(Sweep::retrograde),
                    Pacing.CODEC.optionalFieldOf("pacing", Pacing.VANILLAS).forGetter(Sweep::pacing),
                ).apply(instance, ::Sweep)
            }
        }
    }

    /**
     * A turn that goes a little way and comes back — a wobble rather than a circuit.
     *
     * The difference between this and a [Sweep] is the whole of what a stack buys: a sweep about one axis
     * and an oscillation about another, at a two-to-one ratio, is a figure of eight; a slow shallow
     * oscillation under a sweep is a precessing orbit; and neither is a case anything had to be taught.
     */
    data class Oscillate(
        val axis: Direction.Axis,
        val amplitudeDegrees: Float,
        val period: Int,
        val phaseDegrees: Float = 0.0f,
    ) : Motion {
        override val periodTicks: Int get() = period
        override val kindKey: String get() = OSCILLATE

        override fun turn(frame: Quaternionf, dayTime: Long): Quaternionf {
            val turns = dayTime.toDouble() / period + phaseDegrees / DEGREES_PER_TURN
            val swing = Math.sin(turns * Math.PI * 2.0).toFloat() * amplitudeDegrees
            return frame.turnedAbout(axis, swing)
        }

        companion object {
            val MAP_CODEC: MapCodec<Oscillate> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Direction.Axis.CODEC.fieldOf("axis").forGetter(Oscillate::axis),
                    Codec.FLOAT.fieldOf("amplitude").forGetter(Oscillate::amplitudeDegrees),
                    Codec.INT.fieldOf("period").forGetter(Oscillate::period),
                    Codec.FLOAT.optionalFieldOf("phase", 0.0f).forGetter(Oscillate::phaseDegrees),
                ).apply(instance, ::Oscillate)
            }
        }
    }

    companion object {
        const val TURN = "turn"
        const val SWEEP = "sweep"
        const val OSCILLATE = "oscillate"

        const val DEGREES_PER_TURN = 360.0f

        /**
         * Vanilla's day begins a quarter turn round, so that day time zero puts the sun where vanilla puts
         * it. Easy to get backwards, and wrong by six hours when you do.
         */
        const val QUARTER_TURN = 0.25

        private val KINDS: Map<String, MapCodec<out Motion>> = mapOf(
            TURN to Turn.MAP_CODEC,
            SWEEP to Sweep.MAP_CODEC,
            OSCILLATE to Oscillate.MAP_CODEC,
        )

        val CODEC: Codec<Motion> = Codec.STRING.dispatch("kind", Motion::kindKey) { key ->
            KINDS[key] ?: Turn.MAP_CODEC
        }

        /** JOML turns in the local frame, which is what makes a fold over a list compose outermost-first. */
        private fun Quaternionf.turnedAbout(axis: Direction.Axis, degrees: Float): Quaternionf {
            val radians = Math.toRadians(degrees.toDouble()).toFloat()
            return when (axis) {
                Direction.Axis.X -> rotateX(radians)
                Direction.Axis.Y -> rotateY(radians)
                Direction.Axis.Z -> rotateZ(radians)
            }
        }
    }
}

/**
 * How a sweep is paced around its circle.
 *
 * Not a detail: **vanilla's sun is not on a constant rate**, and a body meant to be vanilla's sun has to
 * share its curve or it will be in the wrong place all day and only right at noon and midnight.
 */
enum class Pacing(private val key: String) {
    /**
     * Vanilla's own curve (`DimensionType.timeOfDay`), which makes a body linger near the horizon and hurry
     * through the zenith. The default, so a stack that says nothing behaves as everything did before it.
     */
    VANILLAS("vanillas") {
        override fun applyTo(raw: Double): Double {
            val eased = 0.5 - Math.cos(raw * Math.PI) / 2.0
            return (raw * 2.0 + eased) / 3.0
        }
    },

    /** A constant rate, which is what a companion body or an epicycle usually wants. */
    EVEN("even") {
        override fun applyTo(raw: Double): Double = raw
    },
    ;

    abstract fun applyTo(raw: Double): Double

    companion object {
        val CODEC: Codec<Pacing> = Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: VANILLAS },
            { it.key },
        )
    }
}
