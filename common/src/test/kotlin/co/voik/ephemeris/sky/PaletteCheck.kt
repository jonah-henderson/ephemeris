package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * What a [Palette] draws a texel as, and that the levels it keys vanilla's sun by are the sprite's own.
 */
class PaletteCheck : FunSpec({

    val black = Rgba(0.0f, 0.0f, 0.0f, 1.0f)
    val glow = Rgba(0.4f, 0.4f, 0.5f, 0.0f)
    val palette = Palette.ofVanillaSun(centre = black, ring = black, rim = black, glow = glow)

    test("black is drawn as nothing, and the glow fades in towards its brightest") {
        check(palette.at(0.0f) == Rgba.CLEAR) { "black came out as ${palette.at(0.0f)}" }
        val halfway = palette.at(Palette.VANILLA_SUN_GLOW / 2)
        check(abs(halfway.red - glow.red / 2) < CLOSE) { "half the glow's brightness gave $halfway" }
        check(palette.at(Palette.VANILLA_SUN_GLOW) == glow) { "the glow's brightest was not the glow" }
    }

    test("the disc is drawn as what it was given, and holds above it") {
        check(palette.at(Palette.VANILLA_SUN_RIM) == black) { "the rim gave ${palette.at(Palette.VANILLA_SUN_RIM)}" }
        check(palette.at(1.0f) == black) { "white gave ${palette.at(1.0f)}" }
    }

    test("the levels vanilla's sun is keyed by are the colours its sprite carries") {
        val sprite = javaClass.getResourceAsStream("/assets/minecraft/textures/environment/celestial/sun.png")
            ?.use(ImageIO::read)
            ?: error("vanilla's sun was not on the classpath")
        val levels = (0..<sprite.width).flatMap { x -> (0..<sprite.height).map { y -> sprite.getRGB(x, y) } }
            .map { packed ->
                val red = ((packed shr 16) and 0xFF) / 255.0f
                val green = ((packed shr 8) and 0xFF) / 255.0f
                val blue = (packed and 0xFF) / 255.0f
                red * Palette.SEEN_AS_RED + green * Palette.SEEN_AS_GREEN + blue * Palette.SEEN_AS_BLUE
            }
            .toSet()
        val disc = levels.filter { it > DISC_FROM }.sortedDescending()
        val brightestGlow = levels.filter { it <= DISC_FROM }.max()
        val keyed = listOf(Palette.VANILLA_SUN_CENTRE, Palette.VANILLA_SUN_RING, Palette.VANILLA_SUN_RIM)
        check(disc.size == keyed.size && disc.zip(keyed).all { (seen, key) -> abs(seen - key) < CLOSE }) {
            "the sun's disc is $disc, keyed as $keyed"
        }
        check(abs(brightestGlow - Palette.VANILLA_SUN_GLOW) < CLOSE) {
            "the sun's glow peaks at $brightestGlow, keyed as ${Palette.VANILLA_SUN_GLOW}"
        }
    }
})

private const val CLOSE = 0.002f

/** Between the glow's brightest and the rim's, where the sprite has no colour at all. */
private const val DISC_FROM = 0.5f
