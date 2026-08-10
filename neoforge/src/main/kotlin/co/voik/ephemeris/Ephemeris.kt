package co.voik.ephemeris

import co.voik.ephemeris.sky.LevelAppearance
import co.voik.ephemeris.sky.LevelLookPayload
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * Ephemeris on NeoForge.
 *
 * The Fabric half's note applies here too: everything below is bookkeeping every mod with runtime levels
 * would otherwise write, so it is written once and a consumer's own code is only ever
 * `LevelAppearance.give`.
 */
@Mod("ephemeris")
class Ephemeris(eventBus: IEventBus) {

    init {
        RuntimeLevelPlatform.use(NeoForgeRuntimeLevelPlatform())

        // Payload registration is a mod-bus event, so unlike Fabric's it cannot be a call from init.
        eventBus.addListener(::onRegisterPayloads)

        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onPlayerChangedDimension)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    /**
     * **Bump [PAYLOAD_VERSION] whenever the codec or the handler's meaning changes**, or two modded ends
     * negotiate a channel they disagree about.
     *
     * The handler lands the look in [LevelLooks], which is plain data with no client types, so nothing here
     * is dist-sensitive and it can stay on the common class.
     */
    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar(PAYLOAD_VERSION)
            .playToClient(LevelLookPayload.TYPE, LevelLookPayload.STREAM_CODEC) { payload, _ ->
                LevelLooks.remember(payload)
            }
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        (event.entity as? ServerPlayer)?.let { LevelAppearance.joined(it) }
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let { LevelAppearance.left(it) }
    }

    /** The watchdog for lazy delivery — silent when delivery is eager, which is the default. */
    private fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        (event.entity as? ServerPlayer)?.let { LevelAppearance.arrived(it, event.to) }
    }

    /** These keys mean nothing in the next world an integrated server opens, and ids repeat. */
    private fun onServerStopped(event: ServerStoppedEvent) {
        LevelAppearance.forgetAll()
    }

    private companion object {
        const val PAYLOAD_VERSION = "1"
    }
}
