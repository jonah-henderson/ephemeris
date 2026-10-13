package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.RainLook
import net.minecraft.client.multiplayer.ClientLevel

/** What `WeatherEffectRendererMixin` and `ClientLevelMixin` ask of a level's [RainLook]. */
object LevelRain {

    /** The rain [level] is drawn with — vanilla's where nothing said otherwise. */
    @JvmStatic
    fun of(level: ClientLevel): RainLook = LevelLooks.of(level.dimension())?.rain ?: ORDINARY

    private val ORDINARY = RainLook()
}
