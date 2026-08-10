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

    /** Must match `BODY_ABOVE` in `celestial_cut.fsh`, which is where the number does its work. */
    val cutAt = 42.0

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


    test("the gap the cut sits in is still there") {
        // The shader's one constant has to fall in a *gap* — a luminance nothing in the sprite occupies — or
        // it clips something. Rather than split background from body by hand, this finds each sprite's own
        // widest empty band and asks whether the cut is inside it. That way a repaint moves the evidence
        // instead of quietly moving the picture.
        var checked = 0
        for (shape in shapes) {
            val bytes = spriteBytes(shape) ?: continue
            checked++
            val palette = paletteOf(bytes)
            val greys = colourCounts(bytes, palette.size / 3).keys
                .map { luminanceOf(palette, it) }
                .distinct()
                .sorted()
            check(greys.size > 1) { "${shape.path} has one colour in it" }

            // Not the *widest* band — that is often between two bright colours and says nothing. What
            // matters is that nothing sits near the cut on either side, so no pixel is a rounding error away
            // from changing which side of it it falls on.
            val nearestBelow = greys.filter { it < cutAt }.maxOrNull()
            val nearestAbove = greys.filter { it > cutAt }.minOrNull()
            check(nearestAbove != null) {
                "${shape.path} is entirely below the cut at $cutAt — it would vanish altogether"
            }
            val room = Math.min(cutAt - (nearestBelow ?: 0.0), nearestAbove - cutAt)
            check(room > 3.0) {
                "${shape.path} has a colour within $room of the cut at $cutAt (nearest below $nearestBelow, " +
                    "nearest above $nearestAbove). The cut has to fall in a band nothing occupies, or it " +
                    "clips part of the picture"
            }
        }
        check(checked > 0) { "No sprites were read, so the gap is unwatched" }
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
private fun colourCounts(bytes: ByteArray, paletteEntries: Int): Map<Int, Int> {
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

    val counts = mutableMapOf<Int, Int>()
    var above = ByteArray(width)
    var read = 0
    repeat(height) {
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
            if (restored < paletteEntries) counts[restored] = (counts[restored] ?: 0) + 1
        }
        above = row
    }
    return counts
}

/** PNG's own predictor: whichever of left, above or above-left the gradient points nearest. */
private fun paethOf(left: Int, up: Int, upLeft: Int): Int {
    val estimate = left + up - upLeft
    val toLeft = Math.abs(estimate - left)
    val toUp = Math.abs(estimate - up)
    val toUpLeft = Math.abs(estimate - upLeft)
    return if (toLeft <= toUp && toLeft <= toUpLeft) left else if (toUp <= toUpLeft) up else upLeft
}
