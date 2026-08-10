package co.voik.ephemeris

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.WeatherData

/**
 * Weather a level keeps for itself.
 *
 * **26.1 moved the schedule onto the server**: `WeatherData` — the rain, thunder and clear timers with
 * their flags — is one object on `MinecraftServer`, and `ServerLevel.getWeatherData()` does nothing but
 * return it. Everything a level then *does* with weather is already per level: `rainLevel` and
 * `thunderLevel` are fields on `Level`, `isRaining()` reads those rather than the schedule, and
 * `advanceWeatherCycle` broadcasts to that dimension's players. So the only reason every dimension agrees
 * about the weather is that they all compute from one schedule.
 *
 * Hand a level its own and the rest follows with nothing further written: vanilla runs the cycle, moves the
 * timers, interpolates the levels and tells that dimension's players. `isRainingAt`, mob spawning,
 * `tickThunder`, snow and ice, crops and `/weather` all come along, and **the client needs nothing at all**.
 *
 * Worth knowing what this also fixes: vanilla has *every* level decrementing the same shared timers, so
 * weather cycles faster the more dimensions are loaded. Taking a level out of that is, if anything, more
 * correct than leaving it in.
 */
object LevelWeather {

    private val sources = mutableListOf<LevelWeatherSource>()

    /** Offer a level weather of its own. */
    fun source(source: LevelWeatherSource) {
        sources += source
    }

    /**
     * The first answer anything gives, or null for a level that should share the server's.
     *
     * Asked by the Mixin. Public because a Java Mixin cannot see a Kotlin `internal`, whose name is
     * mangled — not because a consumer has business calling it.
     */
    fun of(level: ServerLevel): WeatherData? = sources.firstNotNullOfOrNull { it.weatherFor(level) }
}

/** Decides whether a level has weather of its own. */
fun interface LevelWeatherSource {
    /** This level's own schedule, or **null** to leave it sharing the server's. */
    fun weatherFor(level: ServerLevel): WeatherData?
}
