package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Eclipse
import co.voik.ephemeris.sky.LevelDaylight
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector3fc
import org.joml.Vector4f

/**
 * The air under an eclipse: the light falling to night's, the sky and the fog to a dusky violet, the clouds to
 * night's grey, all by the deciding sun's [Eclipse.shadow], the same curve the light the game reads follows.
 *
 * **Laid over every other painter's air**, so an Age whose sky is red goes dark from red rather than having its
 * eclipse painted over.
 */
object EclipseAir {

    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        val shadow = Shadow(level)
        layers.addTimeBasedLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR) { was, _ ->
            Mth.lerp(shadow.now(), was, NIGHT_SKY_LIGHT_FACTOR)
        }
        layers.addTimeBasedLayer(EnvironmentAttributes.SKY_COLOR) { was, _ -> toward(was, Eclipse.TOTALITY_SKY, shadow.now()) }
        layers.addTimeBasedLayer(EnvironmentAttributes.FOG_COLOR) { was, _ -> toward(was, Eclipse.TOTALITY_FOG, shadow.now()) }
        layers.addTimeBasedLayer(EnvironmentAttributes.SKY_LIGHT_COLOR) { was, _ -> toward(was, Eclipse.TOTALITY_LIGHT, shadow.now()) }
        layers.addTimeBasedLayer(EnvironmentAttributes.CLOUD_COLOR) { was, _ ->
            val how = shadow.now()
            if (how <= 0.0f) was else Vector4f(
                Mth.lerp(how, was.x(), CLOUDS.red),
                Mth.lerp(how, was.y(), CLOUDS.green),
                Mth.lerp(how, was.z(), CLOUDS.blue),
                was.w(),
            )
        }
        return layers
    }

    private fun toward(was: Vector3fc, under: Rgba, how: Float) =
        if (how <= 0.0f) was else Rgba.of(was).lerp(under, how).rgb()

    /**
     * The shadow over one level, worked out once a tick: every layer above asks it, several times a frame, and an
     * eclipse moves far too slowly for a partial tick to show.
     */
    private class Shadow(private val level: ClientLevel) {
        private var askedAt = Long.MIN_VALUE
        private var shadow = 0.0f

        fun now(): Float {
            val clock = level.defaultClockTime
            if (clock != askedAt) {
                askedAt = clock
                val look = LevelLooks.of(level.dimension())
                shadow = look?.let { LevelDaylight.eclipseOf(it, clock) }?.shadow ?: 0.0f
            }
            return shadow
        }
    }

    /** Vanilla's own sky-light factor at night (`Timelines.NIGHT_SKY_LIGHT_FACTOR`). */
    private const val NIGHT_SKY_LIGHT_FACTOR = 0.24f


    /** Vanilla's clouds at night (`Timelines.NIGHT_CLOUD_COLOR_MULTIPLIER`). */
    private val CLOUDS = Rgba(0.10f, 0.10f, 0.15f)
}
