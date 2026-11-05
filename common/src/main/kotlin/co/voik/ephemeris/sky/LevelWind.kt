package co.voik.ephemeris.sky

import net.minecraft.world.level.Level

/** The wind a level was described with, on either side, for whatever drifts with it. */
object LevelWind {

    /** The wind [level] was written with, or [Wind.STILL] where nothing said. */
    @JvmStatic
    fun of(level: Level): Wind = LevelLooks.anywhere(level)?.wind ?: Wind.STILL

    /** How it blows in [level] now, [partialTicks] into the tick. */
    @JvmStatic
    @JvmOverloads
    fun now(level: Level, partialTicks: Float = 0.0f): Wind.Blowing =
        of(level).at(level.gameTime + partialTicks.toDouble())
}
