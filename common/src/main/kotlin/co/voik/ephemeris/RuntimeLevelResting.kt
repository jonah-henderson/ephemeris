package co.voik.ephemeris

import com.mojang.serialization.Codec
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType

/**
 * Which runtime levels stand still: those nothing has used for a while, past the few used most recently.
 *
 * A level nobody is in still ticks in vanilla — its weather and clock run, its raids are counted, and each of
 * those marks its data for saving — and so does whatever a mod does to every level each tick. For the three
 * vanilla levels that is nothing; for a couple of hundred runtime levels it is most of a tick. A **resting** level
 * is not ticked at all: its weather, its clock and everything else in it wait exactly where they were, and go on
 * from there when it wakes.
 *
 * **A level rests only when there is nothing in it to go on.** No player, no chunk ticket of any kind and no chunk
 * loaded, and none of those for [QUIET_TICKS] — so whatever was let go has unloaded and been saved before it stops.
 * Anything that would use a resting level wakes it, because using one means asking for a chunk: a player arriving,
 * a forced chunk, a command reaching in. It wakes on the next tick, and is the most recently used again.
 *
 * **And only past the [keepAwake] most recently used**, so a server with few levels sees no difference at all. The
 * default keeps every level awake; a caller sets how many from its own config.
 *
 * Only levels [RuntimeLevels] opened ever rest — never a vanilla level and never another mod's.
 */
object RuntimeLevelResting {

    /** When each level this library opened was last used, kept with the level so it moves and goes with it. */
    private val uses = HashMap<ResourceKey<Level>, LevelUseData>()

    /** The game time each level was opened this session; a level rests only once it has been quiet since. */
    private val openedAt = HashMap<ResourceKey<Level>, Long>()

    /** The levels kept awake however long since they were used — the [keepAwake] used most recently. */
    private var awake: Set<ResourceKey<Level>> = emptySet()
    private var rankedAt = 0
    private var ranked = false

    /** The levels that did not tick this tick, as the tick decided. */
    private val resting = HashSet<ResourceKey<Level>>()
    private var restingWhenTold = 0

    private var keptAwake: () -> Int = { Int.MAX_VALUE }

    /**
     * How many runtime levels to keep ticking, most recently used first, however long since anybody used them.
     * Asked about once a second, so a config read live takes effect within one.
     */
    fun keepAwake(count: () -> Int) {
        keptAwake = count
    }

    /** Whether [level] is resting this tick — for work a caller does to every level that a resting one should not get. */
    fun isResting(level: ServerLevel): Boolean = level.dimension() in resting

    internal fun opened(level: ServerLevel) {
        val dimension = level.dimension()
        uses[dimension] = level.dataStorage.computeIfAbsent(LevelUseData.TYPE)
        openedAt[dimension] = level.server.overworld().gameTime
        ranked = false
    }

    /** These keys mean nothing in the next world an integrated server opens. */
    fun forgetAll() {
        uses.clear()
        openedAt.clear()
        resting.clear()
        awake = emptySet()
        ranked = false
        restingWhenTold = 0
    }

    internal fun forget(dimension: ResourceKey<Level>) {
        uses -= dimension
        openedAt -= dimension
        resting -= dimension
        ranked = false
    }

    /**
     * Whether [level] should sit this tick out, deciding it for the tick. Asked by the Mixin at the head of every
     * level's tick, so a level is woken by the same check that would have rested it.
     */
    @JvmStatic
    fun rests(level: ServerLevel): Boolean {
        val dimension = level.dimension()
        val use = uses[dimension] ?: return false
        val now = level.server.overworld().gameTime
        if (isInUse(level)) {
            use.usedAt(now)
            resting -= dimension
            return false
        }
        rankIfDue(level.server)
        val quietSince = maxOf(use.lastUsed, openedAt[dimension] ?: now)
        val rests = now - quietSince >= QUIET_TICKS && dimension !in awake
        if (rests) resting += dimension else resting -= dimension
        return rests
    }

    /**
     * Anything at all in it: a player, a ticket of any kind — a forced chunk, a portal, a chunk somebody is waiting
     * on, which only a ticking level would go on to load — or a chunk still loaded, which may still be saving.
     */
    private fun isInUse(level: ServerLevel): Boolean =
        level.players().isNotEmpty() ||
            level.chunkSource.ticketStorage.hasTickets() ||
            level.chunkSource.loadedChunksCount > 0

    private fun rankIfDue(server: MinecraftServer) {
        if (ranked && server.tickCount - rankedAt < RANKING_TICKS) return
        ranked = true
        rankedAt = server.tickCount
        if (resting.size != restingWhenTold) {
            restingWhenTold = resting.size
            RuntimeLevelLog.info("${resting.size} of ${uses.size} runtime levels resting")
        }
        val keeping = keptAwake().coerceAtLeast(0)
        awake = if (keeping >= uses.size) {
            uses.keys.toSet()
        } else {
            uses.entries.sortedByDescending { it.value.lastUsed }.take(keeping).map { it.key }.toSet()
        }
    }

    /** How long a level must have had nothing in it before it may rest: ten seconds. */
    private const val QUIET_TICKS = 200L

    /** How often the most recently used are counted again. */
    private const val RANKING_TICKS = 20
}

/** When a runtime level was last used, as the overworld's game time, which goes on across restarts. */
internal class LevelUseData(lastUsed: Long = NEVER) : SavedData() {

    var lastUsed: Long = lastUsed
        private set

    fun usedAt(time: Long) {
        if (time == lastUsed) return
        lastUsed = time
        setDirty()
    }

    companion object {
        /** A level never used since this was kept: the least recently used of all. */
        const val NEVER = Long.MIN_VALUE

        private val CODEC: Codec<LevelUseData> =
            Codec.LONG.fieldOf("last_used").codec().xmap(::LevelUseData, LevelUseData::lastUsed)

        /** In the level's own data storage, so it moves with the level and goes when it does. */
        val TYPE: SavedDataType<LevelUseData> = SavedDataType(
            Identifier.fromNamespaceAndPath(LevelClockPayload.NAMESPACE, "use"),
            { LevelUseData() },
            CODEC,
            DataFixTypes.LEVEL,
        )
    }
}
