package co.voik.ephemeris

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Fabric's half of [RuntimeLevelPlatform].
 *
 * Nothing to invalidate — Fabric's tick loop walks the level map itself — so the lifecycle half is only the
 * courtesy of telling other mods, through the same event Fabric API fires for the levels built at boot.
 *
 * `canSend` is asked even though Fabric tolerates not asking, so both loaders behave the same way against a
 * vanilla client rather than one of them being accidentally more forgiving.
 */
class FabricRuntimeLevelPlatform : RuntimeLevelPlatform {

    override fun levelOpened(server: MinecraftServer, level: ServerLevel) {
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level)
    }

    override fun levelClosing(server: MinecraftServer, level: ServerLevel) {
        ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level)
    }

    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        if (!ServerPlayNetworking.canSend(player, payload.type())) return
        ServerPlayNetworking.send(player, payload)
    }
}
