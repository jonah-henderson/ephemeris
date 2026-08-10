package co.voik.ephemeris

import co.voik.ephemeris.sky.LevelAppearance
import co.voik.ephemeris.sky.LevelLookPayload
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

/**
 * Ephemeris on Fabric.
 *
 * **Everything here is bookkeeping a consumer would otherwise write, and get subtly wrong.** Registering the
 * payload on both sides, telling a joining client what it needs, dropping what a leaving one was told, and
 * watching for an arrival nothing announced are all the same job in every mod that has runtime levels — so
 * they are done once, here, and a consumer's own code is only ever `LevelAppearance.give`.
 */
fun init() {
    RuntimeLevelPlatform.use(FabricRuntimeLevelPlatform())

    // On both sides from the main entrypoint, not the client one: Fabric requires the type registered at
    // each end, and registering it twice throws. Common init is the only place that is true of.
    PayloadTypeRegistry.clientboundPlay().register(LevelLookPayload.TYPE, LevelLookPayload.STREAM_CODEC)

    ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> LevelAppearance.joined(handler.player) }
    ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> LevelAppearance.left(handler.player) }

    // The watchdog for lazy delivery — silent when delivery is eager, which is the default.
    ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, destination ->
        LevelAppearance.arrived(player, destination.dimension())
    }

    // These keys mean nothing in the next world an integrated server opens, and ids repeat.
    ServerLifecycleEvents.SERVER_STOPPED.register { _ -> LevelAppearance.forgetAll() }
}
