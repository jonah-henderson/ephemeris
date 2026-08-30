package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.JsonOps
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import io.kotest.core.spec.style.FunSpec

/**
 * That a curtain comes on the nights it said it would, and arrives at the client as the curtain that was
 * sent.
 *
 * **The whole of what can be checked without a window**, and worth checking for exactly that reason: an
 * aurora is drawn on the client, from arithmetic every client does for itself, so a frequency that is
 * subtly wrong shows up as "it never seems to come" months later and never as a failure.
 *
 * The **order** of the ramp is the other half. It is the one thing about an aurora a writer states outright,
 * it passes through a sentence, a recipe, a codec and a texture on its way to the sky, and every one of
 * those is a chance to reverse it silently.
 */
class AuroraCheck : FunSpec({

    fun sent(aurora: Aurora): Aurora {
        val written = Aurora.CODEC.encodeStart(JsonOps.INSTANCE, aurora).getOrThrow()
        return Aurora.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
    }

    /** A run of nights, as the strengths they came at — nought for the nights it did not come. */
    fun nights(aurora: Aurora, howMany: Int): List<Float> = (0..<howMany).map { aurora.strengthOn(it.toLong()) }

    val plenty = 4000

    test("an aurora that never comes comes on no night") {
        val never = Aurora(frequency = 0.0f)
        check(nights(never, plenty).all { it == 0.0f }) {
            "A curtain asked for on no nights came on ${nights(never, plenty).count { it > 0.0f }} of $plenty"
        }
    }

    test("an aurora that always comes comes on every night") {
        val always = Aurora(frequency = 1.0f, seed = 77L)
        val missed = nights(always, plenty).count { it <= 0.0f }
        check(missed == 0) { "A curtain asked for on every night missed $missed of $plenty" }
    }

    test("how often it comes is what it was asked for") {
        // Wide enough to catch a fencepost or an inverted comparison, narrow enough to catch a rate that is
        // half what it should be — which is the failure a hash written by hand actually makes.
        val tolerance = 0.04f
        for (asked in listOf(0.1f, 0.35f, 0.5f, 0.8f)) {
            val aurora = Aurora(frequency = asked, seed = asked.toRawBits().toLong())
            val came = nights(aurora, plenty).count { it > 0.0f }.toFloat() / plenty
            check(Math.abs(came - asked) <= tolerance) {
                "A curtain asked for on $asked of nights came on $came of them"
            }
        }
    }

    test("a night it comes is never fainter than its faintest, nor brighter than whole") {
        val shown = nights(Aurora(frequency = 0.6f, seed = 5L), plenty).filter { it > 0.0f }
        check(shown.isNotEmpty()) { "No night came at all, so this checks nothing" }
        check(shown.all { it >= Aurora.FAINTEST }) { "A night came at ${shown.min()}, under ${Aurora.FAINTEST}" }
        check(shown.all { it <= 1.0f }) { "A night came at ${shown.max()}, over a whole one" }
    }

    test("the nights it comes are not all alike") {
        // The point of reading how far inside its threshold a roll landed. If this collapses, `frequency`
        // has quietly become a switch and every aurora burns identically on the nights it burns.
        val shown = nights(Aurora(frequency = 0.7f, seed = 11L), plenty).filter { it > 0.0f }
        check(shown.distinct().size > plenty / 10) {
            "Only ${shown.distinct().size} distinct strengths over $plenty nights, so the nights are one night"
        }
    }

    test("two clients agree and two levels do not") {
        val one = Aurora(frequency = 0.4f, seed = 1234L)
        val same = Aurora(frequency = 0.4f, seed = 1234L)
        val other = Aurora(frequency = 0.4f, seed = 1235L)
        check(nights(one, 500) == nights(same, 500)) { "The same curtain gave two answers for the same nights" }
        check(nights(one, 500) != nights(other, 500)) { "Two seeds gave one run of nights" }
    }

    test("a ramp arrives crown first, however many colours it has") {
        val crownToHem = listOf(
            Rgba(1.0f, 0.0f, 0.0f),
            Rgba(1.0f, 0.6f, 0.0f),
            Rgba(0.9f, 0.9f, 0.0f),
            Rgba(0.0f, 0.9f, 0.2f),
            Rgba(0.0f, 0.4f, 1.0f),
            Rgba(0.5f, 0.0f, 0.9f),
        )
        val arrived = sent(Aurora(colours = crownToHem)).colours
        check(arrived == crownToHem) { "A six-colour ramp arrived as $arrived" }
        check(arrived.first() == crownToHem.first()) { "The crown arrived at the hem, so the ramp is upside down" }
    }

    test("a ramp with nothing in it reads as the ordinary one") {
        // A packet can carry an empty list; a client that threw while decoding a sky would be worse than a
        // level that looks slightly wrong.
        check(Aurora(colours = emptyList()).ramp == Aurora.ORDINARY_RAMP) {
            "An empty ramp read as ${Aurora(colours = emptyList()).ramp}"
        }
    }

    test("everything else about a curtain survives the trip") {
        val elaborate = Aurora(
            colours = listOf(Rgba(0.1f, 0.2f, 0.3f, 0.8f), Rgba(0.4f, 0.5f, 0.6f)),
            glow = 1.7f,
            breadth = 0.42f,
            height = 0.83f,
            frequency = 0.19f,
            bearingDegrees = 237.0f,
            ground = AuroraGround.WHERE_IT_SNOWS,
            seed = -998877L,
        )
        check(sent(elaborate) == elaborate) { "A curtain changed on the way: ${sent(elaborate)}" }
    }

    test("a curtain that says nothing about the ground may be seen over any") {
        // The neutral answer is the default on purpose: a library aurora that silently never comes is a
        // worse thing to meet first than one that comes everywhere.
        check(Aurora().ground == AuroraGround.ANYWHERE) { "An undescribed curtain carries a rule nobody asked for" }
        check(sent(Aurora()).ground == AuroraGround.ANYWHERE) { "The default did not survive the trip" }
    }

    test("a sky carries its curtain to the client") {
        // The field is optional on the codec, so the way it fails is by being dropped rather than mangled.
        val withOne = SkySpec.VANILLA.copy(aurora = Aurora(frequency = 0.5f, seed = 3L))
        val written = SkySpec.CODEC.encodeStart(JsonOps.INSTANCE, withOne).getOrThrow()
        val arrived = SkySpec.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
        check(arrived.aurora == withOne.aurora) { "A sky arrived with ${arrived.aurora}" }
    }

    test("a sky with a curtain is still an ordinary sky") {
        // Deliberate: an aurora is drawn on an overlay that runs whether we claimed the sky or vanilla did,
        // so hanging one over a level must not take vanilla's own sun and moon away from it.
        check(SkySpec.VANILLA.copy(aurora = Aurora()).isOrdinary) {
            "An aurora made the sky extraordinary, so vanilla's sun will be replaced by a copy of itself"
        }
    }
})

/**
 * That a curtain survives **the wire**, which is a different question from surviving a codec.
 *
 * `LevelLookPayload` sends a spec through `ByteBufCodecs.fromCodec`, so what actually crosses is an NBT
 * round-trip inside a byte buffer — not the JSON one every other check here exercises. The two are not the
 * same test, and this codebase has already lost an afternoon to the difference: the Spire's unbroken cloud
 * decks encoded as vanilla-cut ones and arrived full of holes, with everything offline saying they were
 * fine (Jonah, 2026-08-27, walked).
 *
 * An aurora is the worst possible thing to make that mistake with, because it is invisible most nights by
 * design — a field that failed to cross would read as bad luck for weeks.
 */
class AuroraOnTheWireCheck : FunSpec({

    // Built rather than `Level.OVERWORLD`, whose class cannot initialise without a bootstrapped game —
    // and none of what crosses here needs one.
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

    val elaborate = Aurora(
        colours = listOf(Rgba(0.9f, 0.1f, 0.2f), Rgba(0.2f, 0.9f, 0.4f), Rgba(0.3f, 0.2f, 0.9f)),
        glow = 1.31f,
        breadth = 0.73f,
        height = 0.68f,
        frequency = 0.43f,
        bearingDegrees = 214.0f,
        ground = AuroraGround.WHERE_IT_SNOWS,
        seed = 4242L,
    )

    test("a curtain crosses the wire unchanged") {
        val arrived = crossed(LevelLook(SkySpec.VANILLA.copy(aurora = elaborate))).sky.aurora
        check(arrived != null) { "The curtain did not cross at all, so no client will ever draw one" }
        check(arrived == elaborate) { "The curtain crossed as $arrived" }
    }

    test("the ramp crosses in the order it was written") {
        val arrived = crossed(LevelLook(SkySpec.VANILLA.copy(aurora = elaborate))).sky.aurora?.colours
        check(arrived == elaborate.colours) { "The ramp arrived as $arrived" }
    }

    test("a sky with no curtain crosses as a sky with no curtain") {
        // The optional field's other half: absent must arrive absent rather than as a default one.
        check(crossed(LevelLook(SkySpec.VANILLA)).sky.aurora == null) {
            "A sky nobody hung a curtain in arrived wearing one"
        }
    }
})
