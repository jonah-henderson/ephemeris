package co.voik.ephemeris.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * How much of the ground around the viewer is cold enough to snow — the reader for
 * [co.voik.ephemeris.sky.AuroraGround.WHERE_IT_SNOWS].
 *
 * **Sampled around the camera rather than under it**, and then **faded in time**, which are two different
 * softenings doing two different jobs. A single test at the camera flickers along a ragged biome edge, where
 * a ring gives a border about as wide as the ring is; and a share that snapped between two values would read
 * as a bug however soft the border was, where a fade over a couple of seconds reads as the sky changing its
 * mind. Neither alone is enough.
 *
 * **Wall-clock, not ticks**, as [co.voik.ephemeris.client] drawing generally is: this is a client-only fade
 * with nothing to agree with anyone else about, and reading a clock beats threading a delta through a
 * renderer.
 */
object SnowLine {

    /**
     * What share of the ground seen from [eye] would take snow, `0..1`, faded toward wherever it is going.
     *
     * Resampled a few times a second and interpolated between, because the walk between two samples is
     * shorter than the fade and nobody can see the difference.
     */
    fun shareSeenFrom(level: ClientLevel, eye: Vec3): Float {
        val now = System.currentTimeMillis()
        // Arriving somewhere is not a border you walked over: a level's own answer starts where it starts
        // rather than fading up out of the last one's.
        if (level.dimension() != sampledIn) {
            sampledIn = level.dimension()
            sampled = shareAround(level, eye)
            shown = sampled
            movedAt = now
            return shown
        }
        if (now - sampledAt >= BETWEEN_SAMPLES) {
            sampled = shareAround(level, eye)
            sampledAt = now
        }
        shown = faded(shown, sampled, now - movedAt)
        movedAt = now
        return shown
    }

    /**
     * The share of [ringAround] that would take snow, asked of the level directly. Impure and the only part
     * of this that is.
     */
    private fun shareAround(level: ClientLevel, eye: Vec3): Float {
        val seaLevel = level.seaLevel
        fun takesSnow(at: Vec3): Boolean {
            val position = BlockPos.containing(at)
            return level.getBiome(position).value().coldEnoughToSnow(position, seaLevel)
        }
        val asked = ringAround(eye, reach())
        return asked.count(::takesSnow).toFloat() / asked.size
    }

    /**
     * The positions a share is taken over: the eye itself and [AROUND] evenly spaced about it at [radius].
     *
     * Pure, so how wide the border is and how evenly it is sampled can be checked without a level.
     */
    fun ringAround(eye: Vec3, radius: Double): List<Vec3> = listOf(eye) + (0..<AROUND).map { step ->
        val bearing = step.toDouble() / AROUND * FULL_TURN
        eye.add(sin(bearing) * radius, 0.0, cos(bearing) * radius)
    }

    /**
     * [shown] moved toward [target] over [elapsedMillis] — an exponential approach, so the fade has no
     * corner at either end and no dependence on the frame rate.
     *
     * Pure, and the half of this worth checking: a fade that overshoots or never arrives is invisible in a
     * screenshot and obvious in a number.
     */
    fun faded(shown: Float, target: Float, elapsedMillis: Long): Float {
        if (elapsedMillis <= 0L) return shown
        val howFar = 1.0 - exp(-elapsedMillis.toDouble() / FADE_MILLIS)
        return (shown + (target - shown) * howFar).toFloat().coerceIn(0.0f, 1.0f)
    }

    /**
     * How far out the ring is asked, in blocks — [RING_BLOCKS], or as far as the view reaches where that is
     * nearer.
     *
     * **A chunk the client has not loaded answers with whatever its empty chunk holds**, so a ring reaching
     * past the view would report a plain where it should report ice, and the aurora would die when somebody
     * turned their render distance down. Two chunks is the least Minecraft allows, which is where the clamp
     * bites.
     */
    private fun reach(): Double {
        val seen = Minecraft.getInstance().options.renderDistance().get() * SECTION - SECTION / 2
        return RING_BLOCKS.coerceAtMost(seen.toDouble())
    }

    /**
     * How wide the border is, in blocks — a soft edge about twice this across, half of it either side of the
     * biome's own line.
     */
    private const val RING_BLOCKS = 24.0

    /** How many points around the eye. Eight is every compass point, and the ring is a softening not a survey. */
    private const val AROUND = 8

    /** How long the fade takes to cover most of its distance, in milliseconds. */
    private const val FADE_MILLIS = 900.0

    /** How often the ground is asked. Far shorter than the fade, so nothing between two samples is visible. */
    private const val BETWEEN_SAMPLES = 500L

    private const val SECTION = 16

    private const val FULL_TURN = 2.0 * Math.PI

    private var sampledIn: ResourceKey<Level>? = null
    private var sampled = 0.0f
    private var shown = 0.0f
    private var sampledAt = 0L
    private var movedAt = 0L
}
