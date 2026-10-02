package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A sprite's colours **drawn as other colours**, keyed by how bright each texel of the sprite is.
 *
 * Each entry's colour is premultiplied: its red, green and blue are the light the texel gives out, and its
 * alpha is how much of the sky behind it is hidden. So one draw can paint a disc that covers — a black sun
 * hides the sky behind it, which adding never can — and a glow that adds, as vanilla's does.
 *
 * Between two entries the colour is blended, below the dimmest it fades to nothing at black, and above the
 * brightest it holds.
 */
data class Palette(val entries: List<Entry>) {

    /** A texel [brightness] bright, `0..1`, is drawn as [becomes]. */
    data class Entry(val brightness: Float, val becomes: Rgba)

    private val ascending: List<Entry> = entries.sortedBy(Entry::brightness)

    /** What a texel [brightness] bright is drawn as. */
    fun at(brightness: Float): Rgba {
        val above = ascending.firstOrNull { it.brightness >= brightness } ?: return ascending.last().becomes
        val belowIndex = ascending.indexOf(above) - 1
        val below = ascending.getOrNull(belowIndex) ?: Entry(NO_BRIGHTNESS, Rgba.CLEAR)
        val span = above.brightness - below.brightness
        if (span <= 0.0f) return above.becomes
        return below.becomes.lerp(above.becomes, (brightness - below.brightness) / span)
    }

    companion object {
        private const val NO_BRIGHTNESS = 0.0f

        /**
         * How bright each of vanilla's sun colours is, as the shader measures a texel (`recoloured_body.fsh`).
         *
         * Vanilla's sun is three rings of disc — #FFFFD9 at the centre, #FFFFAA, then #FFD54A at the rim — and
         * a glow fading out from #282810 at its brightest. `PaletteCheck` reads them off the sprite.
         */
        const val VANILLA_SUN_CENTRE = 0.989f
        const val VANILLA_SUN_RING = 0.976f
        const val VANILLA_SUN_RIM = 0.831f
        const val VANILLA_SUN_GLOW = 0.150f

        /** The luma weights the shader measures a texel by — [Rgba]'s own. */
        const val SEEN_AS_RED = 0.2126f
        const val SEEN_AS_GREEN = 0.7152f
        const val SEEN_AS_BLUE = 0.0722f

        /**
         * Vanilla's sun with each of its parts drawn as another colour. The glow fades out from [glow] as
         * vanilla's does.
         */
        fun ofVanillaSun(centre: Rgba, ring: Rgba, rim: Rgba, glow: Rgba): Palette = Palette(
            listOf(
                Entry(VANILLA_SUN_GLOW, glow),
                Entry(VANILLA_SUN_RIM, rim),
                Entry(VANILLA_SUN_RING, ring),
                Entry(VANILLA_SUN_CENTRE, centre),
            ),
        )

        private val ENTRY_CODEC: Codec<Entry> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("brightness").forGetter(Entry::brightness),
                Rgba.CODEC.fieldOf("becomes").forGetter(Entry::becomes),
            ).apply(instance, ::Entry)
        }

        val CODEC: Codec<Palette> = ENTRY_CODEC.listOf().xmap(::Palette, Palette::entries)
    }
}
