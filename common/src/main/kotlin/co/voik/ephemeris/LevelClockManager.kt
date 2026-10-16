package co.voik.ephemeris

import co.voik.ephemeris.sky.ClockPace
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.clock.ClockState
import net.minecraft.world.clock.ClockTimeMarker
import net.minecraft.world.clock.PackedClockStates
import net.minecraft.world.clock.ServerClockManager
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.level.gamerules.GameRules
import java.util.stream.Stream

/**
 * One level's clocks: **its own for the clock its dimension type names, and the server's for every other** —
 * see [LevelClocks].
 *
 * A subclass only because `ServerLevel.clockManager()` is declared to return one. Everything the server's
 * manager does to a clock it owns is redone here for the level's own, and passed through for the rest; the
 * private `modifyClock` that tells every player is never reached, since what changes here is this level's alone.
 */
class LevelClockManager internal constructor(
    private val level: ServerLevel,
    startsAt: Long,
) : ServerClockManager(PackedClockStates.EMPTY) {

    /** The level's own reading of its clock — the one instance every timeline sampler of the level holds. */
    private val own = ServerClockInstance().apply { loadFrom(ClockState(startsAt, NO_PARTIAL, ORDINARY_RATE, false)) }

    private val shared: ServerClockManager get() = level.server.clockManager()

    /** The clock this level keeps for itself, or null for a dimension type that names none. */
    private val ownClock: Holder<WorldClock>? get() = level.dimensionType().defaultClock().orElse(null)

    /** The hours the level's own clock can be moved to — vanilla's for that clock, read as its `init` reads them. */
    private val markers: Map<ResourceKey<ClockTimeMarker>, ClockTimeMarker> by lazy {
        val clock = ownClock ?: return@lazy emptyMap()
        buildMap {
            level.registryAccess().lookupOrThrow(Registries.TIMELINE).listElements().forEach { timeline ->
                timeline.value().registerTimeMarkers { key, marker -> if (marker.clock() == clock) put(key, marker) }
            }
        }
    }

    private fun isOwn(clock: Holder<WorldClock>): Boolean = clock == ownClock

    /** The level's own clock as it stands, for saving and for telling. */
    val state: ClockState get() = own.packState()

    /** Takes up [saved] in place of where the clock was told to start — a level opened again after a restart. */
    fun resume(saved: ClockState) = own.loadFrom(saved)

    /** The last pace the level's sky set, so a `/time rate` given by hand holds until the sky changes. */
    private var kept: ClockPace? = null

    /** Runs the level's own clock at [pace] — what its sky says the day is ([ClockPace]) — once each time it changes. */
    fun keepPace(pace: ClockPace) {
        if (pace == kept) return
        kept = pace
        changeOwn { it.copyWith(rate = pace.rate, paused = pace.paused) }
    }

    /** One tick of the level's own clock, under the game rule that stops every clock. */
    fun advance() {
        if (level.server.globalGameRules.get(GameRules.ADVANCE_TIME)) own.tick()
    }

    override fun getInstance(definition: Holder<WorldClock>): ServerClockInstance =
        if (isOwn(definition)) own else shared.getInstance(definition)

    override fun setTotalTicks(clock: Holder<WorldClock>, totalTicks: Long) {
        if (!isOwn(clock)) return shared.setTotalTicks(clock, totalTicks)
        changeOwn { it.copyWith(totalTicks = totalTicks, partialTick = NO_PARTIAL) }
    }

    override fun addTicks(clock: Holder<WorldClock>, ticks: Int) {
        if (!isOwn(clock)) return shared.addTicks(clock, ticks)
        changeOwn { it.copyWith(totalTicks = (it.totalTicks() + ticks).coerceAtLeast(0L)) }
    }

    override fun setPaused(clock: Holder<WorldClock>, paused: Boolean) {
        if (!isOwn(clock)) return shared.setPaused(clock, paused)
        changeOwn { it.copyWith(paused = paused) }
    }

    override fun setRate(clock: Holder<WorldClock>, rate: Float) {
        if (!isOwn(clock)) return shared.setRate(clock, rate)
        changeOwn { it.copyWith(rate = rate) }
    }

    override fun moveToTimeMarker(clock: Holder<WorldClock>, timeMarkerId: ResourceKey<ClockTimeMarker>): MoveResult {
        if (!isOwn(clock)) return shared.moveToTimeMarker(clock, timeMarkerId)
        val marker = markers[timeMarkerId] ?: return MoveResult.NO_TIME_MARKER_FOUND
        if (marker.occursAt(own.totalTicks())) return MoveResult.NOT_MOVED
        changeOwn { it.copyWith(totalTicks = marker.resolveTimeToMoveTo(it.totalTicks()), partialTick = NO_PARTIAL) }
        return MoveResult.MOVED
    }

    override fun isAtTimeMarker(clock: Holder<WorldClock>, timeMarkerId: ResourceKey<ClockTimeMarker>): Boolean {
        if (!isOwn(clock)) return shared.isAtTimeMarker(clock, timeMarkerId)
        return markers[timeMarkerId]?.occursAt(own.totalTicks()) == true
    }

    override fun commandTimeMarkersForClock(clock: Holder<WorldClock>): Stream<ResourceKey<ClockTimeMarker>> {
        if (!isOwn(clock)) return shared.commandTimeMarkersForClock(clock)
        return markers.filterValues { it.showInCommands() }.keys.stream()
    }

    override fun createFullSyncPacket() = shared.createFullSyncPacket()

    override fun packState(): PackedClockStates = shared.packState()

    /** Moves the level's own clock, and tells everyone straight away — a sleep or a `/time` is not a slow beat. */
    private fun changeOwn(change: (ClockState) -> ClockState) {
        own.loadFrom(change(own.packState()))
        LevelClocks.changed(level)
    }

    private fun ClockState.copyWith(
        totalTicks: Long = totalTicks(),
        partialTick: Float = partialTick(),
        rate: Float = rate(),
        paused: Boolean = paused(),
    ) = ClockState(totalTicks, partialTick, rate, paused)

    private companion object {
        const val NO_PARTIAL = 0.0f
        const val ORDINARY_RATE = 1.0f
    }
}
