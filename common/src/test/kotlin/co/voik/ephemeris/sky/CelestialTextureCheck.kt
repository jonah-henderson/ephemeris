package co.voik.ephemeris.sky

import io.kotest.core.spec.style.FunSpec

/**
 * That vanilla's celestial sprites have no transparency — **the fact `Blending.ADDS` is the default for**.
 *
 * It reads like an odd thing to check until you have seen what happens when it is forgotten. The sun and all
 * eight moon shapes are indexed colour with no `tRNS` chunk, so every pixel is fully opaque and about two
 * thirds of each is near-black. Vanilla never notices, drawing both bodies additively where black
 * contributes nothing. Draw one *covering* and the black is painted across the sky — a moon in daylight
 * inside an enormous dark square, which is what a walk found.
 *
 * So this is the evidence for the default, kept where the default is. If Mojang ever ships these with an
 * alpha channel, this fails and `Blending.COVERS` becomes worth offering as more than an escape hatch.
 */
class CelestialTextureCheck : FunSpec({

    /** Past the eight-byte signature, a PNG is a chain of length-tagged chunks. */
    val pastTheSignature = 8

    /** The `tRNS` chunk is the only place an indexed PNG can carry transparency. */
    fun hasTransparency(bytes: ByteArray): Boolean {
        var at = pastTheSignature
        while (at + 8 <= bytes.size) {
            val length = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            val kind = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            if (kind == "tRNS") return true
            if (kind == "IEND") return false
            at += 12 + length
        }
        return false
    }

    val shapes = Appearance.MOON_SHAPES + Appearance.SUN_SHAPES

    /** Must match `VANILLAS_MOON` in `Blaze3dSkyCanvas`, which crops to exactly this. */
    val moonFrom = 12
    val moonTo = 19

    fun spriteBytes(shape: net.minecraft.resources.Identifier): ByteArray? =
        javaClass.getResourceAsStream(
            "/assets/${shape.namespace}/textures/environment/celestial/${shape.path}.png",
        )?.readBytes()

    test("not one celestial sprite carries an alpha channel") {
        val opaque = mutableListOf<String>()
        var found = 0
        for (shape in shapes) {
            val bytes = spriteBytes(shape) ?: continue
            found++
            if (!hasTransparency(bytes)) opaque += shape.path
        }
        check(found > 0) {
            "None of vanilla's celestial sprites were on the classpath, so this check is watching nothing. " +
                "It looked under /assets/minecraft/textures/environment/celestial/"
        }
        check(opaque.size == found) {
            "${found - opaque.size} of $found celestial sprites now carry transparency (${shapes.size} known). " +
                "If Mojang has given these an alpha channel, `Blending.COVERS` is no longer a trap and the " +
                "default is worth revisiting"
        }
    }


    test("every phase of the moon lies inside the window we crop to") {
        // **The crop is exact, and this is what keeps it exact.** Vanilla's moon sprites are 32 by 32 with
        // the moon in the middle 8 by 8 and a dark blue night-sky gradient painted all round it. Cropping to
        // that window throws away the sky and keeps every phase whole — including the new moon, whose faint
        // disc a test on *colour* would have hollowed out, since its middle is darker than its rim.
        //
        // If a repaint ever moves the moon or makes it bigger, this says so. Nothing else would: the sky
        // would simply reappear at the edges, or the moon would lose its limb, and both read as "the sky
        // renderer is broken".
        var checked = 0
        for (shape in Appearance.MOON_SHAPES) {
            val bytes = spriteBytes(shape) ?: continue
            checked++
            val palette = paletteOf(bytes)
            val moon = pixelsBrighterThan(bytes, palette, SURROUND_TOPS_OUT_AT)
            check(moon.isNotEmpty()) { "${shape.path} has nothing in it brighter than its surround" }

            val outside = moon.filter { (x, y) ->
                x < moonFrom || x > moonTo || y < moonFrom || y > moonTo
            }
            check(outside.isEmpty()) {
                "${shape.path} has ${outside.size} pixel(s) of moon outside the window $moonFrom..$moonTo, " +
                    "the first at ${outside.first()}. Cropping there would cut the moon itself"
            }
        }
        check(checked == Appearance.MOON_SHAPES.size) {
            "Only $checked of ${Appearance.MOON_SHAPES.size} moon sprites were read, so the window is " +
                "half-watched"
        }
    }
})

private fun paletteOf(bytes: ByteArray): ByteArray {
    var at = 8
    while (at + 8 <= bytes.size) {
        val length = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        if (String(bytes, at + 4, 4, Charsets.ISO_8859_1) == "PLTE") {
            return bytes.copyOfRange(at + 8, at + 8 + length)
        }
        at += 12 + length
    }
    return ByteArray(0)
}

private fun luminanceOf(palette: ByteArray, index: Int): Double {
    val red = palette[index * 3].toInt() and 0xFF
    val green = palette[index * 3 + 1].toInt() and 0xFF
    val blue = palette[index * 3 + 2].toInt() and 0xFF
    return 0.299 * red + 0.587 * green + 0.114 * blue
}

/**
 * Which palette entries the image actually uses.
 *
 * The rows have to be **un-filtered** to read: a PNG row carries a filter byte and its pixels as deltas
 * against the row above or the pixel to the left, so the raw bytes are not palette indices at all. Reading
 * them as though they were says every sprite is one dark colour — which is exactly what the first attempt
 * here claimed, and why this does the work instead of assuming.
 */
/** The surround is a gradient topping out here; anything above it is the moon. */
private const val SURROUND_TOPS_OUT_AT = 23.0

/** Where in the picture anything brighter than [brighterThan] sits. */
private fun pixelsBrighterThan(bytes: ByteArray, palette: ByteArray, brighterThan: Double): List<Pair<Int, Int>> {
    val found = mutableListOf<Pair<Int, Int>>()
    forEachPixel(bytes, palette.size / 3) { x, y, index ->
        if (luminanceOf(palette, index) > brighterThan) found += x to y
    }
    return found
}

/**
 * Walks the picture, handing each pixel's palette index to [each].
 *
 * The rows have to be **un-filtered** to read: a PNG row carries a filter byte and its pixels as deltas
 * against the row above or the pixel to the left, so the raw bytes are not palette indices at all. Reading
 * them as though they were says every sprite is one dark colour — which is exactly what the first attempt
 * here claimed, and why this does the work instead of assuming.
 */
private fun forEachPixel(bytes: ByteArray, paletteEntries: Int, each: (Int, Int, Int) -> Unit) {
    var at = 8
    var width = 0
    var height = 0
    val compressed = java.io.ByteArrayOutputStream()
    while (at + 8 <= bytes.size) {
        val length = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        when (String(bytes, at + 4, 4, Charsets.ISO_8859_1)) {
            "IHDR" -> {
                width = ((bytes[at + 8].toInt() and 0xFF) shl 24) or ((bytes[at + 9].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 10].toInt() and 0xFF) shl 8) or (bytes[at + 11].toInt() and 0xFF)
                height = ((bytes[at + 12].toInt() and 0xFF) shl 24) or ((bytes[at + 13].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 14].toInt() and 0xFF) shl 8) or (bytes[at + 15].toInt() and 0xFF)
            }
            "IDAT" -> compressed.write(bytes, at + 8, length)
        }
        at += 12 + length
    }
    val raw = java.util.zip.InflaterInputStream(
        java.io.ByteArrayInputStream(compressed.toByteArray()),
    ).readBytes()

    var above = ByteArray(width)
    var read = 0
    for (y in 0..<height) {
        val filter = raw[read].toInt() and 0xFF
        read++
        val row = raw.copyOfRange(read, read + width)
        read += width
        // One byte per pixel, so the "pixel to the left" is simply the previous byte.
        for (x in 0..<width) {
            val left = if (x > 0) row[x - 1].toInt() and 0xFF else 0
            val up = above[x].toInt() and 0xFF
            val upLeft = if (x > 0) above[x - 1].toInt() and 0xFF else 0
            val here = row[x].toInt() and 0xFF
            val restored = when (filter) {
                0 -> here
                1 -> here + left
                2 -> here + up
                3 -> here + ((left + up) / 2)
                4 -> here + paethOf(left, up, upLeft)
                else -> here
            } and 0xFF
            row[x] = restored.toByte()
            if (restored < paletteEntries) each(x, y, restored)
        }
        above = row
    }
}

/** PNG's own predictor: whichever of left, above or above-left the gradient points nearest. */
private fun paethOf(left: Int, up: Int, upLeft: Int): Int {
    val estimate = left + up - upLeft
    val toLeft = Math.abs(estimate - left)
    val toUp = Math.abs(estimate - up)
    val toUpLeft = Math.abs(estimate - upLeft)
    return if (toLeft <= toUp && toLeft <= toUpLeft) left else if (toUp <= toUpLeft) up else upLeft
}
