package co.voik.ephemeris.sky

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import net.minecraft.client.renderer.PostChainConfig
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * That a level's filters cross to the client, that a filtered level is never taken for an ordinary one, and that
 * the filter shipped is one vanilla can read — a post effect that fails to load is only logged, on a client.
 */
class LevelFiltersCheck : FunSpec({

    val somewhere: ResourceKey<Level> = ResourceKey.create(
        Registries.DIMENSION,
        Identifier.fromNamespaceAndPath("minecraft", "overworld"),
    )

    fun crossed(look: LevelLook): LevelLook {
        val payload = LevelLookPayload(listOf(LevelLookPayload.Entry(somewhere, look)))
        val buffer = io.netty.buffer.Unpooled.buffer()
        LevelLookPayload.STREAM_CODEC.encode(buffer, payload)
        return LevelLookPayload.STREAM_CODEC.decode(buffer).looks.single().look
    }

    test("a level's filters cross") {
        val grey = LevelLook(SkySpec.VANILLA, filters = listOf(LevelLook.MONOCHROME))
        check(crossed(grey).filters == listOf(LevelLook.MONOCHROME)) { "The filters arrived as ${crossed(grey).filters}" }
    }

    test("a filtered level says something, so nothing declines it") {
        check(!LevelLook(SkySpec.VANILLA, filters = listOf(LevelLook.MONOCHROME)).saysNothing) {
            "A level seen through a filter was taken for one nothing described"
        }
    }

    test("the monochrome filter is a post effect vanilla can read") {
        val path = "/assets/${LevelLook.MONOCHROME.namespace}/post_effect/${LevelLook.MONOCHROME.path}.json"
        val file = requireNotNull(javaClass.getResourceAsStream(path)) { "$path is not shipped" }
        val json = file.reader().use(JsonParser::parseReader)
        val read = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json)
        check(read.isSuccess) { "$path does not read as a post chain: ${read.error().map { it.message() }.orElse("")}" }
    }
})
