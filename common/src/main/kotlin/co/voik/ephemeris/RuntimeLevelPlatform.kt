package co.voik.ephemeris

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The few things about a runtime level that genuinely differ per loader: **telling the loader a level
 * arrived, and telling a client what it looks like**.
 *
 * Everything else — building the level, putting it in the map, the border, the chunk source — is vanilla and
 * lives in `common`. These are not, and it is not a matter of taste:
 *
 * - **NeoForge caches the level array.** `MinecraftServer` keeps a `ServerLevel[]` rebuilt only when
 *   `markWorldsDirty()` is called, and the tick loop walks the array rather than the map. A level added
 *   without it is a level that never ticks. Both that method and `LevelEvent.Load` are NeoForge additions,
 *   invisible to `common`, which compiles against vanilla.
 * - **Fabric has its own event** and no such cache.
 * - **Sending a payload has no shared API at all**, and the loaders disagree about a vanilla client:
 *   NeoForge's `NetworkRegistry.checkPacket` throws when a channel was never negotiated where Fabric sends
 *   and lets the client discard it. So an implementation checks first and a caller may assume sending is
 *   always safe.
 *
 * So the shape is an interface here and one implementation per loader — but **a consumer writes neither**.
 * Ephemeris ships as a mod on each loader, so each half registers its own implementation from its own
 * entrypoint before any consumer runs.
 *
 * **Registered by a call rather than through `ServiceLoader`**, which is worth saying because the service
 * shape is the obvious one. A `META-INF/services` file works within one jar and is a coin toss across two:
 * NeoForge loads mods into module layers, where a provider declared by one module is not automatically
 * visible to a `ServiceLoader.load` in another. A call from the loader half of the same mod cannot fail that
 * way, and it also means a consumer embedding this library can substitute its own implementation — for a
 * test, or for a loader that does not exist yet.
 */
interface RuntimeLevelPlatform {

    /** Called on the server thread immediately after a level has joined the map. */
    fun levelOpened(server: MinecraftServer, level: ServerLevel)

    /** And immediately before one leaves it, while it is still usable. */
    fun levelClosing(server: MinecraftServer, level: ServerLevel)

    /**
     * Sends [payload] to [player], or does nothing where that player cannot receive it — a vanilla client
     * simply keeps vanilla's sky rather than being disconnected over it.
     */
    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)

    companion object {
        @Volatile
        private var registered: RuntimeLevelPlatform? = null

        /** Called by each loader half of this mod as it starts, before any consumer can ask for a level. */
        fun use(platform: RuntimeLevelPlatform) {
            registered = platform
        }

        fun of(): RuntimeLevelPlatform = registered ?: Silent
    }

    /**
     * What is left if the loader half never ran — which should be impossible, and is here because "should be"
     * is not "is". Levels work on Fabric; on NeoForge they never tick, and nothing this side can tell.
     *
     * **Sending says so, once.** A level that looks wrong is the quietest failure in the library — the
     * client draws vanilla's sky, which is a perfectly ordinary thing for a sky to do — so the one place a
     * missing implementation is cheap to name, it gets named.
     */
    private object Silent : RuntimeLevelPlatform {
        private val complained = AtomicBoolean(false)

        override fun levelOpened(server: MinecraftServer, level: ServerLevel) = Unit

        override fun levelClosing(server: MinecraftServer, level: ServerLevel) = Unit

        override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
            if (complained.compareAndSet(false, true)) {
                RuntimeLevelLog.warn(
                    "No RuntimeLevelPlatform is registered, so nothing can be told what a level looks like. " +
                        "Ephemeris' loader half did not start — check that the mod is present for this loader.",
                )
            }
        }
    }
}
