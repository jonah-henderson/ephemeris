package co.voik.ephemeris

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

class RecolouringCheck : FunSpec({

    fun packed(red: Float, green: Float, blue: Float, alpha: Float = 1.0f) = Rgba(red, green, blue, alpha).packed()
    fun Int.colour() = Rgba.of(this)

    val red = Rgba(0.85f, 0.2f, 0.2f)

    test("a mostly white creature drawn red comes out red, not white with red in it") {
        val wolf = IntArray(64) { if (it % 8 == 0) packed(0.3f, 0.25f, 0.2f) else packed(0.92f, 0.92f, 0.9f) }
        val recoloured = Recolouring.recoloured(wolf, red).map { it.colour() }
        val reddish = recoloured.count { it.red > it.green + 0.1f && it.red > it.blue + 0.1f }
        check(reddish > recoloured.size * 3 / 4) { "only $reddish of ${recoloured.size} came out red: ${recoloured.take(3)}" }
    }

    test("a coloured creature keeps its parts apart and its shading") {
        val skin = packed(0.35f, 0.55f, 0.3f)
        val shirt = packed(0.2f, 0.45f, 0.6f)
        val zombie = intArrayOf(skin, skin, shirt, shirt)
        val recoloured = Recolouring.recoloured(zombie, red)
        check(recoloured[0] != recoloured[2]) { "the skin and the shirt came out alike" }
        for (at in zombie.indices) {
            val before = Oklch.of(zombie[at].colour()).lightness
            val after = Oklch.of(recoloured[at].colour()).lightness
            check(abs(before - after) < 0.03f) { "pixel $at went from lightness $before to $after" }
        }
    }

    test("white and black drain the colour and move the lightness, keeping the contrast") {
        val pig = intArrayOf(packed(0.9f, 0.6f, 0.6f), packed(0.6f, 0.35f, 0.35f))
        val white = Recolouring.recoloured(pig, Rgba(0.91f, 0.91f, 0.93f)).map { Oklch.of(it.colour()) }
        val black = Recolouring.recoloured(pig, Rgba(0.1f, 0.1f, 0.12f)).map { Oklch.of(it.colour()) }
        check(white.all { it.chroma < 0.03f } && black.all { it.chroma < 0.03f }) { "colour was left: $white $black" }
        check(white.average { it.lightness } > black.average { it.lightness } + 0.3f) { "white and black came out alike" }
        check(white[0].lightness > white[1].lightness && black[0].lightness > black[1].lightness) { "the contrast was lost" }
    }

    test("what nothing can see through is left as it was") {
        val clear = packed(0.2f, 0.9f, 0.1f, alpha = 0.0f)
        check(Recolouring.recoloured(intArrayOf(clear, packed(0.5f, 0.5f, 0.5f)), red)[0] == clear) { "a clear pixel was recoloured" }
    }
})

private fun <T> List<T>.average(of: (T) -> Float): Float = map(of).sum() / size
