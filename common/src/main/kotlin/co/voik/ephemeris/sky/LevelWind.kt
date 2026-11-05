package co.voik.ephemeris.sky

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * The wind a level was described with, on either side, for whatever drifts with it — or a wind blowing over it
 * instead for a while, as a storm brings one of its own.
 */
object LevelWind {

    /** The wind blowing over [level] now: one blown instead, or the one it was written with, or [Wind.STILL]. */
    @JvmStatic
    fun of(level: Level): Wind =
        blownInstead[Side.of(level) to level.dimension()] ?: LevelLooks.anywhere(level)?.wind ?: Wind.STILL

    /** How it blows in [level] now, [partialTicks] into the tick. */
    @JvmStatic
    @JvmOverloads
    fun now(level: Level, partialTicks: Float = 0.0f): Wind.Blowing =
        of(level).at(level.gameTime + partialTicks.toDouble())

    /**
     * Blow [wind] over [level] in place of the one it was written with, on [level]'s side only, or stop blowing one
     * where [wind] is null. Nothing remembers it: a consumer sets it again for as long as its storm lasts.
     */
    @JvmStatic
    fun blowInstead(level: Level, wind: Wind?) {
        val key = Side.of(level) to level.dimension()
        if (wind == null) blownInstead.remove(key) else blownInstead[key] = wind
    }

    /** Every wind blown instead on the client, for a client leaving a server. */
    @JvmStatic
    fun forgetTheClients() {
        blownInstead.keys.removeIf { (side, _) -> side == Side.CLIENT }
    }

    private enum class Side {
        CLIENT,
        SERVER;

        companion object {
            fun of(level: Level) = if (level.isClientSide) CLIENT else SERVER
        }
    }

    private val blownInstead = ConcurrentHashMap<Pair<Side, ResourceKey<Level>>, Wind>()
}
