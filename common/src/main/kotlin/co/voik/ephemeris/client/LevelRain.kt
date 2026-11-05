package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.LevelWind
import co.voik.ephemeris.sky.RainLook
import net.minecraft.client.multiplayer.ClientLevel

/** What `WeatherEffectRendererMixin` and `ClientLevelMixin` ask of a level's [RainLook]. */
object LevelRain {

    /** The rain [level] is drawn with — vanilla's where nothing said otherwise. */
    @JvmStatic
    fun of(level: ClientLevel): RainLook = LevelLooks.of(level.dimension())?.rain ?: ORDINARY

    private val ORDINARY = RainLook()

    /**
     * How far a column's top stands from its foot, along X and Z, for each block of its height: upwind, so what
     * falls is carried downwind on its way down. Snow is lighter and is carried further.
     */
    data class Lean(val rainX: Float, val rainZ: Float, val snowX: Float, val snowZ: Float) {
        companion object {
            @JvmField
            val UPRIGHT = Lean(0.0f, 0.0f, 0.0f, 0.0f)
        }
    }

    /** How [level]'s rain and snow lean in its wind now, [partialTicks] into the tick. */
    @JvmStatic
    fun leanIn(level: ClientLevel, partialTicks: Float): Lean {
        val blowing = LevelWind.now(level, partialTicks)
        if (blowing.strength <= 0.0f) return Lean.UPRIGHT
        val upwindX = (-blowing.x * blowing.strength).toFloat()
        val upwindZ = (-blowing.z * blowing.strength).toFloat()
        return Lean(
            rainX = upwindX * RAIN_LEAN_AT_FULL_STRENGTH,
            rainZ = upwindZ * RAIN_LEAN_AT_FULL_STRENGTH,
            snowX = upwindX * SNOW_LEAN_AT_FULL_STRENGTH,
            snowZ = upwindZ * SNOW_LEAN_AT_FULL_STRENGTH,
        )
    }

    /** Blocks across per block down in the strongest wind: about 27° off upright for rain, 50° for snow. */
    private const val RAIN_LEAN_AT_FULL_STRENGTH = 0.5f
    private const val SNOW_LEAN_AT_FULL_STRENGTH = 1.2f
}
