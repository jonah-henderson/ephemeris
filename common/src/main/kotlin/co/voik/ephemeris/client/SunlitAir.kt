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
 * The air under a sun that lights the day other than vanilla's does, and under a moon that glares: its light lifts
 * the night by how full it shows and how high it stands ([LevelDaylight.moonlightOf]).
 *
 * Under a dim sun
 * ([co.voik.ephemeris.sky.CelestialBody.dimming]) the light falls toward night's and the sky, the fog and the clouds
 * darken from their own colours, so a red sky under a dim sun is a dark red one. Under a glaring one
 * ([co.voik.ephemeris.sky.CelestialBody.glare]) the light holds up through the twilight it lengthens. Laid over every
 * other painter's air, as [EclipseAir] is.
 */
object SunlitAir {

    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        val sun = Sun(level)
        layers.addTimeBasedLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR) { was, _ ->
            val lingered = Mth.lerp(sun.lingering(), was, DAY_SKY_LIGHT_FACTOR)
            val dimmed = Mth.lerp(sun.dimming(), lingered, NIGHT_SKY_LIGHT_FACTOR)
            val moonlit = Mth.lerp(sun.moonlight() * MOONLIT_SHARE, NIGHT_SKY_LIGHT_FACTOR, DAY_SKY_LIGHT_FACTOR)
            maxOf(dimmed, moonlit)
        }
        layers.addTimeBasedLayer(EnvironmentAttributes.SKY_COLOR) { was, _ -> darkened(was, sun.dimming()) }
        layers.addTimeBasedLayer(EnvironmentAttributes.FOG_COLOR) { was, _ -> darkened(was, sun.dimming()) }
        layers.addTimeBasedLayer(EnvironmentAttributes.CLOUD_COLOR) { was, _ ->
            val kept = 1.0f - sun.dimming() * DARKEST
            if (kept >= 1.0f) was else Vector4f(was.x() * kept, was.y() * kept, was.z() * kept, was.w())
        }
        return layers
    }

    private fun darkened(was: Vector3fc, how: Float): Vector3fc {
        val kept = 1.0f - how * DARKEST
        return if (kept >= 1.0f) was else Vector3f(was).mul(kept)
    }

    /** The sun over one level, worked out once a tick, for [EclipseAir]'s reason. */
    private class Sun(private val level: ClientLevel) {
        private var askedAt = Long.MIN_VALUE
        private var dimming = 0.0f
        private var lingering = 0.0f
        private var moonlight = 0.0f

        fun dimming(): Float = refreshed().dimming

        fun lingering(): Float = refreshed().lingering

        fun moonlight(): Float = refreshed().moonlight

        private fun refreshed(): Sun {
            val clock = level.defaultClockTime
            if (clock != askedAt) {
                askedAt = clock
                val look = LevelLooks.of(level.dimension())
                dimming = look?.let { LevelDaylight.dimmingOf(it, clock) } ?: 0.0f
                lingering = look?.let { LevelDaylight.lingeringOf(it, clock) } ?: 0.0f
                moonlight = look?.let { LevelDaylight.moonlightOf(it, clock) } ?: 0.0f
            }
            return this
        }
    }

    /** Vanilla's own sky-light factor by day and at night (`Timelines`' keyframes). */
    private const val DAY_SKY_LIGHT_FACTOR = 1.0f
    private const val NIGHT_SKY_LIGHT_FACTOR = 0.24f

    /** How far a full glaring moon overhead lifts the night's light toward the day's. */
    private const val MOONLIT_SHARE = 0.45f

    /** How much of their colour the sky, fog and clouds lose under a sun at full dimming. */
    private const val DARKEST = 0.85f
}
