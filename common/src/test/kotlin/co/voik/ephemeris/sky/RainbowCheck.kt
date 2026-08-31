package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey

/**
 * That a bow stands where the geometry puts it, comes on the days it said it would, and arrives at the
 * client as the bow that was sent.
 *
 * **The whole of what can be checked without a window**, and worth it for the aurora's reason: everything
 * here happens on the client from arithmetic every client does for itself, so a rule that is subtly wrong
 * shows up as "I never see one" months later and never as a failure.
 *
 * The geometry is the half that is peculiar to a bow. Where it goes is not written down anywhere — it is
 * implied by where its light stands — so the arithmetic tying the two together is the only place that claim
 * exists at all.
 */
class RainbowCheck : FunSpec({

    fun sent(rainbow: Rainbow): Rainbow {
        val written = Rainbow.CODEC.encodeStart(JsonOps.INSTANCE, rainbow).getOrThrow()
        return Rainbow.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
    }

    /** A run of days, as the strengths they came at — nought for the days it did not come. */
    fun days(rainbow: Rainbow, howMany: Int): List<Float> = (0..<howMany).map { rainbow.strengthOn(it.toLong()) }

    val plenty = 4000

    test("the crown stands as far above the horizon as the light stands below the radius") {
        // The whole geometry in one line, and the one claim everything else here rests on.
        val bow = Rainbow()
        check(bow.crownAboveHorizon(0.0f) == bow.radiusDegrees) { "A light on the horizon gave the wrong crown" }
        check(bow.crownAboveHorizon(20.0f) == bow.radiusDegrees - 20.0f) { "A risen light gave the wrong crown" }
        check(bow.crownAboveHorizon(bow.radiusDegrees) == 0.0f) { "A light at the radius left a crown standing" }
    }

    test("a light too high casts nothing, and one that has set casts nothing") {
        val bow = Rainbow()
        check(bow.castAt(0.0f) == 1.0f) { "A light on the horizon cast ${bow.castAt(0.0f)} of a bow" }
        check(bow.castAt(20.0f) == 1.0f) { "A light well inside the radius cast ${bow.castAt(20.0f)}" }
        check(bow.castAt(bow.radiusDegrees) == 0.0f) { "A light at the radius still cast a bow" }
        check(bow.castAt(bow.radiusDegrees + 10.0f) == 0.0f) { "A light past the radius still cast a bow" }
        check(bow.castAt(-30.0f) == 0.0f) { "A light that has set still cast a bow" }
    }

    test("nothing about that arrives as a jump") {
        // A gate is allowed; a pop is not. Both edges are tapered, so walking the light across either has
        // to change the answer smoothly or a bow will wink out in front of somebody.
        val bow = Rainbow()
        var previous = bow.castAt(-5.0f)
        var worst = 0.0f
        var worstAt = 0.0f
        var altitude = -5.0f
        while (altitude <= bow.radiusDegrees + 5.0f) {
            val now = bow.castAt(altitude)
            val moved = Math.abs(now - previous)
            if (moved > worst) {
                worst = moved
                worstAt = altitude
            }
            previous = now
            altitude += 0.1f
        }
        check(worst < 0.1f) { "A bow's presence jumped by $worst at a light height of $worstAt°" }
    }

    test("a wider bow is one seen for more of the day") {
        // The reason the radius is a dial and not a constant: it is a fact about what the light is bending
        // through, and a world whose rain bends it further has bows when ours would have none.
        val ours = Rainbow()
        val wider = Rainbow(radiusDegrees = 60.0f)
        check(ours.castAt(50.0f) == 0.0f) { "Our own bow reached a light at 50°, which is past its radius" }
        check(wider.castAt(50.0f) > 0.0f) { "A sixty degree bow did not reach a light at 50°" }
    }

    test("a bow that never comes comes on no day") {
        check(days(Rainbow(frequency = 0.0f), plenty).all { it == 0.0f }) { "A bow came on a day it never does" }
    }

    test("a bow that always comes comes on every day") {
        check(days(Rainbow(frequency = 1.0f), plenty).none { it == 0.0f }) { "A bow missed a day it always comes" }
    }

    test("how often it comes is what it was asked for") {
        for (asked in listOf(0.1f, 0.35f, 0.8f)) {
            val came = days(Rainbow(frequency = asked, seed = 11L), plenty).count { it > 0.0f }
            val share = came.toFloat() / plenty
            check(Math.abs(share - asked) < 0.05f) { "A bow asked for $asked came on $share of days" }
        }
    }

    test("a day it comes is never fainter than its faintest, nor brighter than whole") {
        val came = days(Rainbow(frequency = 0.5f, seed = 7L), plenty).filter { it > 0.0f }
        check(came.min() >= Rainbow.FAINTEST) { "A day came at ${came.min()}, under the faintest" }
        check(came.max() <= 1.0f) { "A day came at ${came.max()}, over whole" }
    }

    test("the days it comes are not all alike") {
        // A frequency that only switched would give every day the same bow, which is the failure the
        // how-far-inside reading exists to avoid.
        val came = days(Rainbow(frequency = 0.6f, seed = 3L), plenty).filter { it > 0.0f }
        check(came.toSet().size > 100) { "A bow came at only ${came.toSet().size} strengths over $plenty days" }
    }

    test("two clients agree and two levels do not") {
        val one = Rainbow(frequency = 0.5f, seed = 42L)
        check(days(one, 200) == days(Rainbow(frequency = 0.5f, seed = 42L), 200)) {
            "Two clients told the same thing disagreed about which days a bow comes"
        }
        check(days(one, 200) != days(Rainbow(frequency = 0.5f, seed = 43L), 200)) {
            "Two levels with different seeds took the same days"
        }
    }

    test("a band arrives outermost first, however many colours it has") {
        // The one thing about a bow a writer states outright, and it passes through a sentence, a recipe, a
        // codec and a mesh on its way to the sky — every one of them a chance to reverse it silently.
        val written = listOf(Rgba(1.0f, 0.0f, 0.0f), Rgba(0.0f, 1.0f, 0.0f), Rgba(0.0f, 0.0f, 1.0f))
        check(sent(Rainbow(colours = written)).band == written) { "A band came back as ${sent(Rainbow(colours = written)).band}" }
    }

    test("the ordinary band runs red on the outside to violet within") {
        // Which way round a real one goes, and the default is where the realism lives.
        val band = Rainbow.ORDINARY_SPECTRUM
        check(band.first().red > band.first().blue) { "The outermost colour is not the red end" }
        check(band.last().blue > band.last().red) { "The innermost colour is not the violet end" }
    }

    test("a band with nothing in it reads as the ordinary one") {
        check(Rainbow(colours = emptyList()).band == Rainbow.ORDINARY_SPECTRUM) {
            "An empty band did not fall back, so a client decoding one draws nothing"
        }
    }

    test("the second bow stands outside the first, wider and fainter") {
        val bow = Rainbow()
        check(bow.secondaryRadiusDegrees > bow.radiusDegrees) { "The second bow stands inside the first" }
        check(bow.secondaryWidthDegrees > bow.widthDegrees) { "The second bow is no wider than the first" }
        check(Rainbow.SECONDARY_KEEPS < 1.0f) { "The second bow is as bright as the first" }
    }

    test("what a bow asks of the weather is what it was written to ask") {
        val needsIt = Rainbow(needsRain = 1.0f)
        check(needsIt.wetEnoughAt(0.0f) == 0.0f) { "A bow needing rain showed in a dry sky" }
        check(needsIt.wetEnoughAt(1.0f) == 1.0f) { "A bow needing rain was held back by rain" }
        val needsNone = Rainbow(needsRain = 0.0f)
        check(needsNone.wetEnoughAt(0.0f) == 1.0f) { "A bow needing nothing still waited for rain" }
        check(needsNone.wetEnoughAt(1.0f) == 1.0f) { "A bow needing nothing was changed by rain" }
    }

    test("everything else about a bow survives the trip") {
        val elaborate = Rainbow(
            colours = listOf(Rgba(0.1f, 0.2f, 0.3f, 0.8f), Rgba(0.4f, 0.5f, 0.6f)),
            glow = 1.4f,
            radiusDegrees = 33.0f,
            widthDegrees = 5.5f,
            secondary = false,
            needsRain = 0.25f,
            frequency = 0.19f,
            seed = -998877L,
        )
        check(sent(elaborate) == elaborate) { "A bow changed on the way: ${sent(elaborate)}" }
    }

    test("a sky carries its bow to the client") {
        // The field is optional on the codec, so the way it fails is by being dropped rather than mangled.
        val withOne = SkySpec.VANILLA.copy(rainbow = Rainbow(frequency = 0.5f, seed = 3L))
        val written = SkySpec.CODEC.encodeStart(JsonOps.INSTANCE, withOne).getOrThrow()
        val arrived = SkySpec.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
        check(arrived.rainbow == withOne.rainbow) { "A sky arrived with ${arrived.rainbow}" }
        check(SkySpec.CODEC.parse(JsonOps.INSTANCE, SkySpec.CODEC.encodeStart(JsonOps.INSTANCE, SkySpec.VANILLA).getOrThrow()).getOrThrow().rainbow == null) {
            "A sky nobody wrote a bow into arrived wearing one"
        }
    }

    test("a sky with a bow is still an ordinary sky") {
        // Deliberate, as an aurora is: a bow is drawn on an overlay that runs whether we claimed the sky or
        // vanilla did, so writing one must not take vanilla's own sun and moon away from a level.
        check(SkySpec.VANILLA.copy(rainbow = Rainbow()).isOrdinary) {
            "A bow made the sky extraordinary, so vanilla's sun will be replaced by a copy of itself"
        }
    }
})

/**
 * That a bow survives the trip a bow actually takes.
 *
 * `LevelLookPayload` sends a spec through `ByteBufCodecs.fromCodec`, so what crosses is an **NBT**
 * round-trip inside a byte buffer, not the JSON one the checks above exercise. The two are not the same
 * test, and this codebase has already lost an afternoon to the difference (see `AuroraOnTheWireCheck`).
 *
 * A bow is nearly as bad a thing to make that mistake with as a curtain: it is absent most of the time by
 * design, so a field that failed to cross would read as bad luck rather than as a fault. `secondary` is the
 * one to watch — a boolean is a byte in NBT, and one arriving wrong gives every level in the world two bows
 * or none with nothing anywhere saying so.
 */
class RainbowOnTheWireCheck : FunSpec({

    // Built rather than `Level.OVERWORLD`, whose class cannot initialise without a bootstrapped game.
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

    val elaborate = Rainbow(
        colours = listOf(Rgba(0.9f, 0.1f, 0.2f), Rgba(0.2f, 0.9f, 0.4f), Rgba(0.3f, 0.2f, 0.9f)),
        glow = 1.31f,
        radiusDegrees = 37.5f,
        widthDegrees = 3.25f,
        secondary = false,
        needsRain = 0.4f,
        frequency = 0.43f,
        seed = 4242L,
    )

    test("a bow crosses the wire unchanged") {
        val arrived = crossed(LevelLook(SkySpec.VANILLA.copy(rainbow = elaborate))).sky.rainbow
        check(arrived != null) { "The bow did not cross at all, so no client will ever draw one" }
        check(arrived == elaborate) { "The bow crossed as $arrived" }
    }

    test("whether there is a second bow crosses") {
        // A boolean is a byte in NBT and an optional codec entry drops silently, which between them are two
        // ways for every level in the world to get the wrong number of arcs and no error anywhere.
        val single = crossed(LevelLook(SkySpec.VANILLA.copy(rainbow = elaborate))).sky.rainbow
        check(single?.secondary == false) { "A bow written with one arc arrived with ${single?.secondary}" }
        val both = Rainbow(secondary = true)
        check(crossed(LevelLook(SkySpec.VANILLA.copy(rainbow = both))).sky.rainbow?.secondary == true) {
            "A bow written with two arcs arrived with one"
        }
    }

    test("the band crosses in the order it was written") {
        val arrived = crossed(LevelLook(SkySpec.VANILLA.copy(rainbow = elaborate))).sky.rainbow?.colours
        check(arrived == elaborate.colours) { "The band arrived as $arrived" }
    }

    test("a sky with no bow crosses as a sky with no bow") {
        // The optional field's other half: absent must arrive absent rather than as a default one, or every
        // level anybody ever described gets bows it never asked for.
        check(crossed(LevelLook(SkySpec.VANILLA)).sky.rainbow == null) {
            "A sky nobody wrote a bow into arrived wearing one"
        }
    }
})
