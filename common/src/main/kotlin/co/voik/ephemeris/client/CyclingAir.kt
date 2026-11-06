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
        turning(layers, level, EnvironmentAttributes.SKY_LIGHT_COLOR, cycles.light)
        turning(layers, level, EnvironmentAttributes.AMBIENT_LIGHT_COLOR, cycles.light)
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
    ) {
        if (cycle == null) return
        layers.addTimeBasedLayer(attribute) { was, _ -> cycle.over(Rgba.of(was), level.gameTime).rgb() }
    }

    private fun turnedWithAlpha(was: Vector4fc, cycle: HueCycle, gameTime: Long): Vector4fc {
        val turned = cycle.over(Rgba(was.x(), was.y(), was.z(), was.w()), gameTime)
        return Vector4f(turned.red, turned.green, turned.blue, was.w())
    }
}
