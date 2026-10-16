package co.voik.ephemeris.client

import co.voik.ephemeris.LevelClocks
import net.minecraft.client.ClientClockManager
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.Holder
import net.minecraft.world.clock.ClockState
import net.minecraft.world.clock.WorldClock

/**
 * A client level's clocks: **its own, where the server said it keeps one, and the connection's otherwise** — the
 * client half of [LevelClocks].
 *
 * Every client level answers with one of these, since the level is built — and its attribute system with it —
 * before anything has said whether it keeps a clock of its own. So it is decided as each reading is asked for,
 * and a level that was never told of one reads the connection's clocks exactly as vanilla's would.
 */
class LevelClientClock(private val level: ClientLevel, private val shared: ClientClockManager) : ClientClockManager() {

    private val reading = Reading()

    override fun getInstance(definition: Holder<WorldClock>): ClientClockInstance {
        val isOwn = definition == level.dimensionType().defaultClock().orElse(null)
        val told = if (isOwn) LevelClocks.toldOf(level.dimension(), level.gameTime) else null
        if (told == null) return shared.getInstance(definition)
        reading.state = told
        return reading
    }

    /** The connection's clocks are still run by the connection's manager; the level's own runs from its telling. */
    override fun tick(gameTime: Long) = shared.tick(gameTime)

    /** The level's own clock as it was last worked out — re-read every time it is asked for. */
    private class Reading : ClientClockInstance() {
        var state = ClockState(0L, 0.0f, 1.0f, false)

        override fun totalTicks(): Long = state.totalTicks()
        override fun partialTick(): Float = state.partialTick()
        override fun rate(): Float = if (state.paused()) 0.0f else state.rate()
        override fun isPaused(): Boolean = state.paused()
    }
}
