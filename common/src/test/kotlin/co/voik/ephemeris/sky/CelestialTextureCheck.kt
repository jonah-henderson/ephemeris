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

    test("not one celestial sprite carries an alpha channel") {
        val opaque = mutableListOf<String>()
        var found = 0
        for (shape in shapes) {
            val path = "/assets/${shape.namespace}/textures/environment/celestial/${shape.path}.png"
            val bytes = javaClass.getResourceAsStream(path)?.readBytes() ?: continue
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

})
