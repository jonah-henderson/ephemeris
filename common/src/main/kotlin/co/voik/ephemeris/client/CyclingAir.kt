package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.HueCycle
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector3fc
import org.joml.Vector4f
import org.joml.Vector4fc
import kotlin.math.pow

/**
 * The air's colours turning round the colour wheel ([co.voik.ephemeris.sky.ColourCycles]): each over whatever the
 * level's own painters made of it, so a red sky turns from red and still darkens at night. Laid under the sunlit and
 * eclipse layers, so an eclipse still darkens a turning sky.
 */
object CyclingAir {

    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        val cycles = LevelLooks.of(level.dimension())?.cycles ?: return layers
        if (cycles.isNone) return layers
        turning(layers, level, EnvironmentAttributes.SKY_COLOR, cycles.sky)
        turning(layers, level, EnvironmentAttributes.FOG_COLOR, cycles.fog)
        turning(layers, level, EnvironmentAttributes.WATER_FOG_COLOR, cycles.waterFog)
        turning(layers, level, EnvironmentAttributes.SKY_LIGHT_COLOR, cycles.light, keepsItsBrightness = true)
        turning(layers, level, EnvironmentAttributes.AMBIENT_LIGHT_COLOR, cycles.light, keepsItsBrightness = true)
        cycles.cloud?.let { cycle ->
            layers.addTimeBasedLayer(EnvironmentAttributes.CLOUD_COLOR) { was, _ -> turnedWithAlpha(was, cycle, level.gameTime) }
        }
        return layers
    }

    private fun turning(
        layers: EnvironmentAttributeSystem.Builder,
        level: ClientLevel,
        attribute: EnvironmentAttribute<Vector3fc>,
        cycle: HueCycle?,
        keepsItsBrightness: Boolean = false,
    ) {
        if (cycle == null) return
        layers.addTimeBasedLayer(attribute) { was, _ ->
            val own = Rgba.of(was)
            val turned = cycle.over(own, level.gameTime)
            (if (keepsItsBrightness) asBrightAs(turned, own) else turned).rgb()
        }
    }

    /**
     * [turned] scaled to [own]'s luminance. Vanilla darkens the night by a blue that holds down red and green, which
     * carry most of the light; turned to orange it would hold down blue instead and the night would come out bright.
     */
    private fun asBrightAs(turned: Rgba, own: Rgba): Rgba {
        val wanted = luminanceOf(own)
        val got = luminanceOf(turned)
        if (got <= 0.0f) return turned
        val scale = wanted / got
        fun scaled(channel: Float) = toGamma(toLinear(channel) * scale).coerceIn(0.0f, 1.0f)
        return Rgba(scaled(turned.red), scaled(turned.green), scaled(turned.blue), turned.alpha)
    }

    private fun luminanceOf(colour: Rgba): Float =
        RED_WEIGHT * toLinear(colour.red) + GREEN_WEIGHT * toLinear(colour.green) + BLUE_WEIGHT * toLinear(colour.blue)

    private fun toLinear(channel: Float): Float = channel.coerceAtLeast(0.0f).pow(GAMMA)
    private fun toGamma(channel: Float): Float = channel.coerceAtLeast(0.0f).pow(1.0f / GAMMA)

    /** Rec. 709's weights, over a plain 2.2 gamma: near enough for keeping a light as bright. */
    private const val RED_WEIGHT = 0.2126f
    private const val GREEN_WEIGHT = 0.7152f
    private const val BLUE_WEIGHT = 0.0722f
    private const val GAMMA = 2.2f

    private fun turnedWithAlpha(was: Vector4fc, cycle: HueCycle, gameTime: Long): Vector4fc {
        val turned = cycle.over(Rgba(was.x(), was.y(), was.z(), was.w()), gameTime)
        return Vector4f(turned.red, turned.green, turned.blue, was.w())
    }
}
