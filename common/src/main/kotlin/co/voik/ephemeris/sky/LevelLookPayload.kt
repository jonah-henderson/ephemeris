package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import io.netty.buffer.ByteBuf
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * What some levels look like, on its way to a client.
 *
 * **This is the whole reason a runtime level can look like anything.** `DimensionType`'s stream codec is
 * `ByteBufCodecs.holderRegistry` and writes registry ids only, so a level's appearance cannot ride in the
 * join packet at all — registries freeze at startup and runtime levels do not exist yet. Plain data on a
 * channel can.
 *
 * **One payload for one level and for the whole set**, because a list covers both: a joining client is told
 * everything at once, and a single change is a list of one. A second type would be a second codec to keep
 * in step for no gain.
 *
 * A client without this library decodes it as `DiscardedPayload` and ignores it rather than disconnecting,
 * so **sending is always safe and arrival is never guaranteed** — which is why the store treats "not told"
 * as its own answer rather than as "ordinary".
 */
data class LevelLookPayload(val looks: List<Entry>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LevelLookPayload> = TYPE

    /** One level, and everything a client should show for it. */
    data class Entry(val dimension: ResourceKey<Level>, val look: LevelLook)

    companion object {
        /**
         * Built with [Identifier.fromNamespaceAndPath] rather than `CustomPacketPayload.createType`, which
         * is `withDefaultNamespace` and would silently claim `minecraft:level_looks`.
         */
        val TYPE: CustomPacketPayload.Type<LevelLookPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(NAMESPACE, "level_looks"),
        )

        /** The library's own namespace, so a consuming mod's id never ends up on the wire. */
        const val NAMESPACE = "ephemeris"

        /**
         * A dimension key writes as a bare `Identifier` and needs no registry — the entire point being that
         * these levels are in none the client can look up. The description rides through
         * [ByteBufCodecs.fromCodec], costing an NBT round-trip and buying one definition of the format.
         */
        private val LOOK_STREAM_CODEC: StreamCodec<ByteBuf, LevelLook> = StreamCodec.composite(
            ByteBufCodecs.fromCodec(SkySpec.CODEC),
            LevelLook::sky,
            ByteBufCodecs.fromCodec(Look.CODEC),
            LevelLook::air,
            ByteBufCodecs.fromCodec(Codec.unboundedMap(Identifier.CODEC, Look.CODEC)),
            LevelLook::corners,
            ::LevelLook,
        )

        private val ENTRY_STREAM_CODEC: StreamCodec<ByteBuf, Entry> = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            Entry::dimension,
            LOOK_STREAM_CODEC,
            Entry::look,
            ::Entry,
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, LevelLookPayload> = StreamCodec.composite(
            ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list()),
            LevelLookPayload::looks,
            ::LevelLookPayload,
        )
    }
}
