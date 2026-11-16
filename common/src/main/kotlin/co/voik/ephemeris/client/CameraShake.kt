package co.voik.ephemeris.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.util.Util
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor

/**
 * The view shaken, as hard as the hardest of whatever a mod says shakes it ([shakenBy]): a tremor, a blast, something
 * landing close. Turned into a jitter of the view's pitch, yaw and roll on top of the hurt tilt, by
 * `GameRendererMixin`, so only what is seen shakes and never where the player looks or aims.
 *
 * **As the screen effects slider says**, as vanilla's nausea and its hurt tilt are, so a player who turned those down
 * is shaken as little. Still while the game is paused.
 */
object CameraShake {

    private val shakers = CopyOnWriteArrayList<() -> Float>()

    /**
     * Asks [shaker] each frame how hard the view shakes: nought for not at all, one for as hard as a blast close by,
     * and up to [HARDEST] beyond it. The hardest of all of them is the shake.
     */
    fun shakenBy(shaker: () -> Float) {
        shakers += shaker
    }

    /** Shakes [pose], the view's tilt, by however hard it shakes now. Called once a frame, by `GameRendererMixin`. */
    @JvmStatic
    fun shake(pose: PoseStack) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isPaused || shakers.isEmpty()) return
        val strength = shakers.maxOf { it() }.coerceIn(0.0f, HARDEST) *
            minecraft.options.screenEffectScale().get().toFloat()
        if (strength <= 0.0f) return
        val time = Util.getMillis() / MILLIS_PER_SECOND * JOLTS_PER_SECOND
        pose.rotateDegrees(Axis.XP, strength * PITCH_DEGREES * wobble(time, PITCH_SALT))
        pose.rotateDegrees(Axis.YP, strength * YAW_DEGREES * wobble(time, YAW_SALT))
        pose.rotateDegrees(Axis.ZP, strength * ROLL_DEGREES * wobble(time, ROLL_SALT))
    }

    /** A smooth jitter between minus one and one, a new heading [JOLTS_PER_SECOND] times a second. */
    private fun wobble(time: Double, salt: Int): Float {
        val whole = floor(time).toInt()
        val between = Mth.smoothstep((time - whole).toFloat())
        return Mth.lerp(between, jolt(whole, salt), jolt(whole + 1, salt))
    }

    private fun jolt(at: Int, salt: Int): Float {
        var mixed = at * MIXER xor salt
        mixed = mixed xor (mixed ushr SHIFT)
        mixed *= MIXER
        mixed = mixed xor (mixed ushr SHIFT)
        return (mixed and LOW_BITS) / LOW_BITS.toFloat() * 2.0f - 1.0f
    }

    /** How hard the view may be shaken, as a share of a blast close by. */
    private const val HARDEST = 1.5f

    /** How far each way the view turns at a shake of one, in degrees: rolling most, as a jolt underfoot does. */
    private const val PITCH_DEGREES = 1.2f
    private const val YAW_DEGREES = 0.9f
    private const val ROLL_DEGREES = 1.8f

    private const val JOLTS_PER_SECOND = 14.0
    private const val MILLIS_PER_SECOND = 1000.0

    private const val MIXER = 0x45D9F3B
    private const val SHIFT = 16
    private const val LOW_BITS = 0xFFFF
    private const val PITCH_SALT = 0x1B873593
    private const val YAW_SALT = 0x2C1B3C6D
    private const val ROLL_SALT = 0x297A2D39
}
