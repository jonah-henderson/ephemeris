package co.voik.ephemeris

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.NetworkRegistry

/**
 * NeoForge's half of [RuntimeLevelPlatform].
 *
 * **`markWorldsDirty` is not optional and its absence is invisible.** NeoForge patches `MinecraftServer` to
 * walk a cached `ServerLevel[]` in the tick loop rather than the map itself, rebuilt only when this is
 * called. A level added without it sits in the map, answers every query, accepts teleports — and never
 * ticks. Nothing logs; it simply does not run.
 *
 * `LevelEvent.Load` is posted for the same reason vanilla's own boot posts it: every NeoForge mod expecting
 * to hear about a level expects to hear about this one.
 *
 * **The `hasChannel` guard is not optional either**: `NetworkRegistry.checkPacket` throws when a channel was
 * never negotiated, so sending blind to a vanilla client would take down the send site rather than being
 * quietly ignored the way Fabric ignores it.
 */
class NeoForgeRuntimeLevelPlatform : RuntimeLevelPlatform {

    override fun levelOpened(server: MinecraftServer, level: ServerLevel) {
        server.markWorldsDirty()
        NeoForge.EVENT_BUS.post(LevelEvent.Load(level))
    }

    override fun levelClosing(server: MinecraftServer, level: ServerLevel) {
        NeoForge.EVENT_BUS.post(LevelEvent.Unload(level))
        server.markWorldsDirty()
    }

    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        if (!NetworkRegistry.hasChannel(player.connection, payload.type().id)) return
        PacketDistributor.sendToPlayer(player, payload)
    }
}
