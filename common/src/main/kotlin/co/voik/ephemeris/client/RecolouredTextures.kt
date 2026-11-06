package co.voik.ephemeris.client

import co.voik.ephemeris.Recolouring
import co.voik.ephemeris.Rgba
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * A texture recoloured toward a colour ([Recolouring]), made the first time it is asked for and kept under an id of its
 * own for as long as the client runs: one per texture and colour, so a red zombie and a red husk share nothing and
 * every red zombie shares one.
 *
 * Only a texture a resource pack ships can be recoloured. One made at run time — a player's skin, a map — has no file
 * to read and comes back as it was, as does one that fails to read.
 */
object RecolouredTextures {

    private val made = HashMap<Pair<Identifier, Int>, Identifier>()

    /** The id of [texture] recoloured toward [colour], made now if it was not yet, or [texture] where it cannot be. */
    fun of(texture: Identifier, colour: Rgba): Identifier = made.getOrPut(texture to colour.packed()) {
        recolouredCopyOf(texture, colour) ?: texture
    }

    /** Lets every copy go, for a client leaving its world: the next one makes what it needs afresh. */
    fun forget() {
        val textures = Minecraft.getInstance().textureManager
        for ((asked, copy) in made) if (copy != asked.first) textures.release(copy)
        made.clear()
    }

    private fun recolouredCopyOf(texture: Identifier, colour: Rgba): Identifier? {
        val resource = Minecraft.getInstance().resourceManager.getResource(texture).orElse(null) ?: return null
        val image = runCatching { resource.open().use(NativeImage::read) }.getOrNull() ?: return null
        val width = image.width
        val pixels = IntArray(width * image.height) { at -> image.getPixel(at % width, at / width) }
        val recoloured = Recolouring.recoloured(pixels, colour)
        for (at in recoloured.indices) image.setPixel(at % width, at / width, recoloured[at])
        val copy = Identifier.fromNamespaceAndPath(NAMESPACE, "recoloured/%08x/${texture.namespace}/${texture.path}".format(colour.packed()))
        Minecraft.getInstance().textureManager.register(copy, DynamicTexture({ "Ephemeris recoloured $texture" }, image))
        return copy
    }

    private const val NAMESPACE = "ephemeris"
}
