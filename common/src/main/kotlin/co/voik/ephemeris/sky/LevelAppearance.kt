package co.voik.ephemeris.sky

import co.voik.ephemeris.RuntimeLevelLog
import co.voik.ephemeris.RuntimeLevelPlatform
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * What each level looks like, and making sure clients know — **the top rung, and the one that should mean a
 * consumer never thinks about networking.**
 *
 * [give] is the whole of the ordinary API: say what a level looks like, and every client that needs to know
 * is told, now and on every future join. There is deliberately no separate "send" — sending *is* what
 * changing means — so there is no call anyone can forget to make, and no way for a client to end up
 * disagreeing with the server about a place.
 *
 * **Delivery is [eagerly] by default, and that should stay true for almost everyone.** A mod with a handful
 * of levels declares them once and every client learns all of them on join: a few hundred bytes each against
 * a one-megabyte packet ceiling, and there is nothing to get wrong because there is nothing to get right.
 *
 * **[lazily] exists for the case eager cannot carry**, which is thousands of levels made at runtime. There
 * the join sweep stops fitting, and telling a client about a level it will never visit is waste. The cost is
 * that the consumer takes on knowing when a player is about to need one — [expecting], and [audience] for
 * the join — and that is a real obligation, which is why [arrived] watches for it being missed rather than
 * letting the failure be silent.
 *
 * Everything here is touched from the server thread, save the mode itself, which a consumer may set during
 * mod init.
 */
object LevelAppearance {

    private val looks = mutableMapOf<ResourceKey<Level>, LevelLook>()
    private val audiences = mutableListOf<LevelLookAudience>()
    private val told = mutableMapOf<UUID, MutableSet<ResourceKey<Level>>>()

    @Volatile
    private var eager = true

    /**
     * Say what a level looks like, and tell everyone who should know.
     *
     * Replaces any earlier answer, which is what lets a level whose description is rewritten show its new
     * appearance rather than the one it opened with.
     */
    fun give(server: MinecraftServer, dimension: ResourceKey<Level>, look: LevelLook) {
        looks[dimension] = look
        val entry = LevelLookPayload.Entry(dimension, look)
        for (player in server.playerList.players) {
            if (shouldKnow(player, dimension)) send(player, listOf(entry))
        }
    }

    /** The same, for a level that is open — which is the usual way to have one. */
    fun give(level: ServerLevel, look: LevelLook) = give(level.server, level.dimension(), look)

    /**
     * Forget a level, for one that has been discarded.
     *
     * Clients keep what they were told until they leave, which costs a few hundred bytes and saves inventing
     * a retraction packet for a level nobody can reach any more.
     */
    fun forget(dimension: ResourceKey<Level>) {
        looks.remove(dimension)
        for (levels in told.values) levels.remove(dimension)
    }

    /** What the server says a level looks like, or **null** where nothing has said. */
    fun of(dimension: ResourceKey<Level>): LevelLook? = looks[dimension]

    /**
     * Tell every client about every level as it joins — the default, and right for almost everyone.
     *
     * There is no arrival hook to get wrong and no route to account for, which matters because a consumer
     * cannot enumerate the ways a player might arrive somewhere: another mod's portal and `/execute in`
     * both defeat any before-travel hook, and neither is unusual.
     */
    fun eagerly() {
        eager = true
    }

    /**
     * Tell a client about a level only when it is about to need one.
     *
     * **Worth taking on past a few thousand levels**, where the join sweep stops fitting in a packet, and
     * not before. It moves a real obligation onto the caller: every route by which a player can arrive
     * somewhere must call [expecting] first, including the routes other mods add. [arrived] exists because
     * that list is impossible to keep complete and the failure is otherwise invisible.
     */
    fun lazily() {
        eager = false
    }

    /**
     * Say which levels a joining player needs to know about, beyond the one they are standing in.
     *
     * A **list of contributors** rather than one answer, because a mod extending another mod's travel has
     * levels to add and cannot see what the first one said. Every audience is asked and the answers pooled.
     *
     * Only consulted under [lazily]; eagerly, everyone already knows everything.
     */
    fun audience(audience: LevelLookAudience) {
        audiences += audience
    }

    /**
     * Tell the library a player is about to need [dimension] — call it **before** moving them.
     *
     * Before, not after, and the ordering is a guarantee rather than a hope: this and the dimension change go
     * down one connection in write order, and the client drains its queue before drawing a frame. Sending
     * afterwards works almost always, which is a different thing from working.
     *
     * Harmless [eagerly], where the player already knows.
     */
    fun expecting(player: ServerPlayer, dimension: ResourceKey<Level>) {
        if (eager) return
        val look = looks[dimension] ?: return
        val fresh = told.getOrPut(player.uuid) { mutableSetOf() }.add(dimension)
        if (fresh) send(player, listOf(LevelLookPayload.Entry(dimension, look)))
    }

    /** Everything a joining player should be told, which [eagerly] is everything there is. */
    fun joined(player: ServerPlayer) {
        val wanted = if (eager) looks.keys.toSet() else wantedBy(player)
        told[player.uuid] = wanted.toMutableSet()
        val entries = wanted.mapNotNull { dimension ->
            looks[dimension]?.let { LevelLookPayload.Entry(dimension, it) }
        }
        if (entries.isNotEmpty()) send(player, entries)
    }

    /** A player has gone, so what they were told goes with them. */
    fun left(player: ServerPlayer) {
        told.remove(player.uuid)
    }

    /**
     * A player has arrived somewhere — **the watchdog that makes [lazily] safe to offer**.
     *
     * Lazy delivery hands the caller an obligation whose failure is otherwise invisible: miss one route and
     * the player sees the wrong sky, with nothing logged and nothing thrown. So an arrival is checked against
     * what that player was told, and a miss is named.
     *
     * It also repairs it, late rather than never, because a sky corrected a frame after arrival beats a wrong
     * one until they leave.
     */
    fun arrived(player: ServerPlayer, dimension: ResourceKey<Level>) {
        if (eager) return
        val look = looks[dimension] ?: return
        if (dimension in knownTo(player)) return
        RuntimeLevelLog.warn(
            "${player.name.string} arrived in ${dimension.identifier()} without having been told what it " +
                "looks like — some route to it does not call LevelAppearance.expecting. Told late.",
        )
        told.getOrPut(player.uuid) { mutableSetOf() }.add(dimension)
        send(player, listOf(LevelLookPayload.Entry(dimension, look)))
    }

    /** Dropped with the server that owned them: these keys mean nothing in the next world, and ids repeat. */
    fun forgetAll() {
        looks.clear()
        told.clear()
    }

    /** Eagerly, everyone. Lazily, only those already told — anyone else hears when they are about to need it. */
    private fun shouldKnow(player: ServerPlayer, dimension: ResourceKey<Level>) =
        eager || dimension in knownTo(player)

    private fun knownTo(player: ServerPlayer): Set<ResourceKey<Level>> = told[player.uuid] ?: emptySet()

    private fun wantedBy(player: ServerPlayer): Set<ResourceKey<Level>> = buildSet {
        // The level they are standing in at minimum — they are about to draw it.
        add(player.level().dimension())
        for (audience in audiences) {
            runCatching { addAll(audience.neededBy(player)) }
                .onFailure { RuntimeLevelLog.warn("An audience threw for ${player.name.string}", it) }
        }
    }

    private fun send(player: ServerPlayer, entries: List<LevelLookPayload.Entry>) {
        RuntimeLevelPlatform.of().sendToPlayer(player, LevelLookPayload(entries))
    }
}

/**
 * Which levels a joining player needs to know about, under [LevelAppearance.lazily].
 *
 * Registered as one of a list rather than as the answer: a mod extending another mod's travel has levels of
 * its own to contribute and cannot see what the first one said.
 */
fun interface LevelLookAudience {
    fun neededBy(player: ServerPlayer): Collection<ResourceKey<Level>>
}
