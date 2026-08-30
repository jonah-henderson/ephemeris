package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/**
 * That a deck arrives at the client as the deck that was sent.
 *
 * **The one thing about a deck that cannot be seen offline, and the one that broke.** Everything else about
 * a `SkySpec` is read back by the command that reports it — on the *server*, from the value it built — so a
 * field the codec cannot carry looks perfect everywhere except out of a window. The Spire's two unbroken
 * sheets encoded as vanilla-cut ones and came out full of holes, with the roil beneath unreadable through
 * them (Jonah, 2026-08-27, walked).
 */
class CloudDeckCheck : FunSpec({

    fun sent(deck: CloudDeck): CloudDeck {
        val written = CloudDeck.CODEC.encodeStart(JsonOps.INSTANCE, deck).getOrThrow()
        return CloudDeck.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
    }

    val slate = Rgba(0.16f, 0.17f, 0.22f)
    val foam = Rgba(0.47f, 0.50f, 0.51f)

    test("a deck with no holes still has none when it arrives") {
        val solid = CloudDeck.solid(height = 160.0, low = slate, high = foam, driftSpeed = 0.045f)
        check(sent(solid).texture == null) {
            "An unbroken deck arrived cut from ${sent(solid).texture}, so it will be drawn with sky between"
        }
        check(sent(solid) == solid) { "An unbroken deck changed on the way: ${sent(solid)}" }
    }

    test("a deck cut from a picture keeps the picture") {
        val cut = CloudDeck(height = 192.0, low = slate, high = foam, driftSpeed = 0.02f)
        check(sent(cut).texture == CloudDeck.VANILLA_CLOUDS) {
            "A deck cut from vanilla's picture arrived with ${sent(cut).texture}"
        }
        check(sent(cut) == cut) { "A cut deck changed on the way: ${sent(cut)}" }
    }

    test("everything else about a deck survives too") {
        // The whole record, because a codec is written once and read back by nobody until it is wrong.
        val elaborate = CloudDeck(
            height = 201.5,
            low = slate,
            high = foam,
            driftSpeed = 0.015f,
            noiseOffsetX = 9000.0,
            noiseOffsetZ = 4000.0,
            halfThickness = 6.0f,
            contrast = 2.4f,
            texture = CloudDeck.VANILLA_CLOUDS,
        )
        check(sent(elaborate) == elaborate) { "A deck changed on the way: ${sent(elaborate)}" }
    }
})
