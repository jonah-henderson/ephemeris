package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey

/**
 * That what a level says about its ground survives the trip, and layers the way every other colour does.
 *
 * **The failure this guards is silence.** A grass colour that fails to cross does not throw — the level
 * simply looks like vanilla, which is what a level with nothing said also looks like. There is no symptom
 * to notice and nothing in a log, so the crossing has to be asserted rather than seen.
 */
class GroundTintCheck : FunSpec({

    fun sent(look: Look): Look {
        val written = Look.CODEC.encodeStart(JsonOps.INSTANCE, look).getOrThrow()
        return Look.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
    }

    val purple = Rgba(0.55f, 0.25f, 0.8f)
    val red = Rgba(0.8f, 0.2f, 0.2f)

    test("a look that says nothing about the ground says nothing") {
        check(Look.NOTHING.saysNothing) { "An empty look stopped being empty" }
        check(Look().grass == null && Look().foliage == null && Look().dryFoliage == null) {
            "A look nobody described came with a ground colour"
        }
    }

    test("a look that only paints the grass is not an empty one") {
        // `saysNothing` decides whether a renderer bothers at all, so a ground-only look reading as empty
        // would be a level that is described and never drawn.
        check(!Look(grass = purple).saysNothing) { "A purple-grassed look read as saying nothing" }
        check(!Look(foliage = purple).saysNothing) { "A purple-leaved look read as saying nothing" }
        check(!Look(dryFoliage = purple).saysNothing) { "A purple-litter look read as saying nothing" }
    }

    test("the three cross the wire and keep to themselves") {
        val painted = Look(grass = purple, foliage = red, dryFoliage = Rgba(0.1f, 0.2f, 0.3f))
        check(sent(painted) == painted) { "A painted ground came back as ${sent(painted)}" }
        // Separately, because one field standing in for another is exactly what an optional codec entry
        // does when its name is wrong, and grass wearing the leaves' colour looks deliberate.
        check(sent(Look(grass = purple)).foliage == null) { "Painting the grass painted the leaves" }
        check(sent(Look(foliage = purple)).grass == null) { "Painting the leaves painted the grass" }
    }

    test("a ground colour lies over a lower look the way every other colour does") {
        val under = Look(grass = red, foliage = red, fog = red)
        val over = Look(grass = purple).over(under)
        check(over.grass == purple) { "The upper look's grass did not win" }
        check(over.foliage == red) { "A look that said nothing about leaves took the upper's null" }
        check(over.fog == red) { "Layering the ground disturbed the air" }
    }

    test("nothing about the ground reaches an ordinary look by accident") {
        check(Look.NOTHING.over(Look.NOTHING).grass == null) { "Two empty looks made a colour between them" }
    }
})

/**
 * And that it survives the trip it actually takes.
 *
 * `LevelLookPayload` goes through `ByteBufCodecs.fromCodec`, so what crosses is an **NBT** round-trip rather
 * than the JSON one above — not the same test, as the cloud decks found out the hard way.
 */
class GroundTintOnTheWireCheck : FunSpec({

    val somewhere: ResourceKey<net.minecraft.world.level.Level> = ResourceKey.create(
        Registries.DIMENSION,
        Identifier.fromNamespaceAndPath("minecraft", "overworld"),
    )

    fun crossed(look: LevelLook): LevelLook {
        val payload = LevelLookPayload(listOf(LevelLookPayload.Entry(somewhere, look)))
        val buffer = io.netty.buffer.Unpooled.buffer()
        LevelLookPayload.STREAM_CODEC.encode(buffer, payload)
        return LevelLookPayload.STREAM_CODEC.decode(buffer).looks.single().look
    }

    val purple = Rgba(0.55f, 0.25f, 0.8f)
    val swamp = Identifier.fromNamespaceAndPath("minecraft", "swamp")

    test("a level's own grass crosses") {
        val painted = LevelLook(SkySpec.VANILLA, air = Look(grass = purple))
        check(crossed(painted).air.grass == purple) { "The grass arrived as ${crossed(painted).air.grass}" }
    }

    test("and a corner of it crosses with its biome") {
        // The whole of `purple grass in swamp`: the corner map is what carries a sited clause, and a key
        // that failed to cross would leave the colour applying nowhere at all.
        val painted = LevelLook(SkySpec.VANILLA, corners = mapOf(swamp to Look(grass = purple)))
        val arrived = crossed(painted)
        check(arrived.corners[swamp]?.grass == purple) { "The swamp's grass arrived as ${arrived.corners}" }
        check(arrived.air.grass == null) { "A corner's colour leaked onto the whole level" }
    }
})
