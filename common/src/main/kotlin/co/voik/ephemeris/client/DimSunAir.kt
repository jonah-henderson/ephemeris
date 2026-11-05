package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelDaylight
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector3f
import org.joml.Vector3fc
import org.joml.Vector4f

/**
 * The air under a dim sun ([co.voik.ephemeris.sky.CelestialBody.dimming]): the light falling toward night's, and the
 * sky, the fog and the clouds darkening from their own colours rather than toward any of ours, so a red sky under a
 * dim sun is a dark red one. Laid over every other painter's air, as [EclipseAir] is.
 */
object DimSunAir {

    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        val dimming = Dimming(level)
        layers.addTimeBasedLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR) { was, _ ->
            Mth.lerp(dimming.now(), was, NIGHT_SKY_LIGHT_FACTOR)
        }
        layers.addTimeBasedLayer(EnvironmentAttributes.SKY_COLOR) { was, _ -> darkened(was, dimming.now()) }
        layers.addTimeBasedLayer(EnvironmentAttributes.FOG_COLOR) { was, _ -> darkened(was, dimming.now()) }
        layers.addTimeBasedLayer(EnvironmentAttributes.CLOUD_COLOR) { was, _ ->
            val kept = 1.0f - dimming.now() * DARKEST
            if (kept >= 1.0f) was else Vector4f(was.x() * kept, was.y() * kept, was.z() * kept, was.w())
        }
        return layers
    }

    private fun darkened(was: Vector3fc, how: Float): Vector3fc {
        val kept = 1.0f - how * DARKEST
        return if (kept >= 1.0f) was else Vector3f(was).mul(kept)
    }

    /** The dimming over one level, worked out once a tick, for [EclipseAir]'s reason. */
    private class Dimming(private val level: ClientLevel) {
        private var askedAt = Long.MIN_VALUE
        private var dimming = 0.0f

        fun now(): Float {
            val clock = level.defaultClockTime
            if (clock != askedAt) {
                askedAt = clock
                dimming = LevelLooks.of(level.dimension())?.let { LevelDaylight.dimmingOf(it, clock) } ?: 0.0f
            }
            return dimming
        }
    }

    /** Vanilla's own sky-light factor at night (`Timelines.NIGHT_SKY_LIGHT_FACTOR`). */
    private const val NIGHT_SKY_LIGHT_FACTOR = 0.24f

    /** How much of their colour the sky, fog and clouds lose under a sun at full dimming. */
    private const val DARKEST = 0.85f
}
