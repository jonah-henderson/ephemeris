package co.voik.ephemeris

import io.netty.buffer.ByteBuf
import co.voik.ephemeris.sky.LevelAppearance
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.clock.ClockState
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * A day a level keeps for itself.
 *
 * **26.1 made the time of day a clock**: a registry entry the server keeps one instance of, read by every level
 * whose dimension type names it. So every runtime level keeps the overworld's hour, and sleeping in one moves it
 * for everybody. The registry is frozen at startup and synced by id, so a clock cannot be added for a level made
 * later.
 *
 * **But nearly everything that reads the time asks the level, not the server**: the timelines that keyframe the
 * sky, the light, monsters burning and the villagers' day; the day count villagers restock by and sieges roll on;
 * the loot time check; [co.voik.ephemeris.sky.LevelClock], which every sky of ours is drawn from. A level that
 * answers `clockManager()` with its own ([LevelClockManager]) carries all of it. What reaches past the level is
 * redirected in `ServerLevelMixin`: the sleep that moves the morning.
 *
 * Kept by dimension, registered before the level is built because its attribute system asks for the clock while
 * the constructor is still running; ticked as the level ticks its time; saved in the level's own data, so a
 * restart picks up where it was; and told to every player on a slow beat and at once on a change. **Every**
 * player rather than the level's own, because a client draws a level it is not in — a panel looking into one.
 */
object LevelClocks {

    private val startsAt = ConcurrentHashMap<ResourceKey<Level>, Long>()
    private val managers = ConcurrentHashMap<ResourceKey<Level>, LevelClockManager>()
    private val saved = ConcurrentHashMap<ResourceKey<Level>, LevelClockData>()

    /** What clients were told, by dimension — plain data, read by the client's own manager. */
    private val told = ConcurrentHashMap<ResourceKey<Level>, Told>()

    /** Gives the level [dimension] a clock of its own, starting at [clock] the first time it is built. */
    internal fun keepOwn(dimension: ResourceKey<Level>, clock: OwnClock?) {
        if (clock == null) startsAt.remove(dimension) else startsAt[dimension] = clock.startsAt
    }

    /**
     * [level]'s own clock manager, or null for a level sharing the server's. Asked by `ServerLevelMixin`; public
     * because a Java Mixin cannot see a Kotlin `internal`.
     */
    @JvmStatic
    fun managerFor(level: ServerLevel): LevelClockManager? {
        val starts = startsAt[level.dimension()] ?: return null
        return managers.computeIfAbsent(level.dimension()) { LevelClockManager(level, starts) }
    }

    /** Takes up what [level]'s clock saved last time, once its data storage can be read. */
    internal fun opened(level: ServerLevel) {
        val manager = managerFor(level) ?: return
        val data = level.dataStorage.computeIfAbsent(LevelClockData.TYPE)
        data.loaded?.let(manager::resume)
        data.keeping = manager
        saved[level.dimension()] = data
    }

    /** One tick of [level]'s own clock, and the beat that tells everyone where it stands. Asked by the Mixin. */
    @JvmStatic
    fun tick(level: ServerLevel) {
        val manager = managers[level.dimension()] ?: return
        LevelAppearance.paceOf(level.dimension())?.let(manager::keepPace)
        manager.advance()
        if (level.server.tickCount % TELLING_THE_CLIENTS == 0) changed(level)
    }

    /** Tells every player where [level]'s own clock stands now, and marks it for saving. */
    internal fun changed(level: ServerLevel) {
        val manager = managers[level.dimension()] ?: return
        saved[level.dimension()]?.setDirty()
        val telling = LevelClockPayload(level.dimension(), manager.state, level.gameTime)
        for (player in level.server.playerList.players) RuntimeLevelPlatform.of().sendToPlayer(player, telling)
    }

    /** Tells a player who has just joined where every level's own clock stands. */
    fun joined(player: ServerPlayer) {
        val server = player.level().server
        for ((dimension, manager) in managers) {
            val level = server.getLevel(dimension) ?: continue
            RuntimeLevelPlatform.of().sendToPlayer(player, LevelClockPayload(dimension, manager.state, level.gameTime))
        }
    }

    /** Drops what [dimension]'s clock was, for a level closed for good. */
    internal fun forget(dimension: ResourceKey<Level>) {
        startsAt.remove(dimension)
        managers.remove(dimension)
        saved.remove(dimension)
    }

    /** These keys mean nothing in the next world an integrated server opens. */
    fun forgetAll() {
        startsAt.clear()
        managers.clear()
        saved.clear()
    }

    /** What a client was told of a level's clock. */
    fun remember(payload: LevelClockPayload) {
        told[payload.dimension] = Told(payload.state, payload.gameTime)
    }

    /** These keys mean nothing on the next server, and an id can be reused. */
    fun forgetWhatWasTold() = told.clear()

    /**
     * Where [dimension]'s own clock stands at [gameTime] by what this client was told, or null where it was told
     * nothing of one — run on from the telling at the clock's rate, as vanilla's client runs its own.
     */
    fun toldOf(dimension: ResourceKey<Level>, gameTime: Long): ClockState? {
        val told = told[dimension] ?: return null
        val state = told.state
        if (state.paused()) return state
        val run = state.partialTick() + (gameTime - told.gameTime).coerceAtLeast(0L) * state.rate().toDouble()
        val whole = Mth.floor(run)
        return ClockState(state.totalTicks() + whole, (run - whole).toFloat(), state.rate(), false)
    }

    private data class Told(val state: ClockState, val gameTime: Long)

    private const val TELLING_THE_CLIENTS = 20
}

/** Where a level's own clock starts the first time it is built, in ticks of its day. */
data class OwnClock(val startsAt: Long)

/**
 * A level's own clock, saved in that level's data — **one record, the state vanilla saves its own clocks as**.
 *
 * Holds the manager while the level is open, so what is saved is where the clock stands then rather than where
 * it stood when it was loaded.
 */
internal class LevelClockData(val loaded: ClockState? = null) : SavedData() {

    var keeping: LevelClockManager? = null

    private val state: ClockState? get() = keeping?.state ?: loaded

    companion object {
        private val CODEC = ClockState.CODEC.optionalFieldOf("clock").codec().xmap(
            { LevelClockData(it.orElse(null)) },
            { Optional.ofNullable(it.state) },
        )

        /**
         * The level's own data storage, so the clock goes when the level does. [DataFixTypes.LEVEL] because the
         * record demands one and this lives beside the level's; nothing here is ever fixed up.
         */
        val TYPE: SavedDataType<LevelClockData> = SavedDataType(
            Identifier.fromNamespaceAndPath(LevelClockPayload.NAMESPACE, "clock"),
            { LevelClockData() },
            CODEC,
            DataFixTypes.LEVEL,
        )
    }
}

/**
 * Where [dimension]'s own clock stood at the server's [gameTime] — enough for a client to run it on by itself
 * until the next telling.
 */
data class LevelClockPayload(
    val dimension: ResourceKey<Level>,
    val state: ClockState,
    val gameTime: Long,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LevelClockPayload> = TYPE

    companion object {
        const val NAMESPACE = "ephemeris"

        val TYPE: CustomPacketPayload.Type<LevelClockPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(NAMESPACE, "level_clock"))

        private val STATE_STREAM_CODEC: StreamCodec<ByteBuf, ClockState> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG,
            ClockState::totalTicks,
            ByteBufCodecs.FLOAT,
            ClockState::partialTick,
            ByteBufCodecs.FLOAT,
            ClockState::rate,
            ByteBufCodecs.BOOL,
            ClockState::paused,
            ::ClockState,
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, LevelClockPayload> = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            LevelClockPayload::dimension,
            STATE_STREAM_CODEC,
            LevelClockPayload::state,
            ByteBufCodecs.VAR_LONG,
            LevelClockPayload::gameTime,
            ::LevelClockPayload,
        )
    }
}
