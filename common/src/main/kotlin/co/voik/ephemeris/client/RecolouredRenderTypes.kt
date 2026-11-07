package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.LevelLooks
import com.mojang.renderpearl.api.textures.GpuSampler
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType
import java.util.WeakHashMap

/**
 * A creature drawn in the colour its level gives its kind ([co.voik.ephemeris.sky.CreatureColours]): while one is
 * submitted ([beginning] to [ending], by `EntityRenderDispatcherMixin`), every render type it submits a model in is
 * exchanged for a copy whose own textures are recoloured ([RecolouredTextures]), by `SubmitNodeCollectionMixin`.
 *
 * **The render type rather than the texture's id**, because several of a creature's render types are built once when
 * their class loads — a spider's eyes, an enderman's, the dragon's — and a swap where an id is asked for would never
 * reach them; and because vanilla caches render types by id, so a swapped id would leak into every creature sharing it.
 * A copy is kept per colour and per render type, weakly, since a few render types (a breeze's wind) are made afresh
 * every frame.
 *
 * Only a creature's own textures change, the ones under `textures/entity/`: what it wears from `equipment/`, what it
 * holds and anything drawn from an atlas keep their colours. Everything here runs on the render thread.
 */
object RecolouredRenderTypes {

    private val drawing = ArrayDeque<Rgba?>()
    private val copies = HashMap<Int, WeakHashMap<RenderType, RenderType>>()

    /** A creature of [type] is being submitted: its render types are recoloured until [ending]. */
    @JvmStatic
    fun beginning(type: EntityType<*>) {
        drawing.addLast(colourFor(type))
    }

    @JvmStatic
    fun ending() {
        drawing.removeLastOrNull()
    }

    /** Lets every copy and its textures go, for a client leaving its world: the next makes what it needs afresh. */
    fun forget() {
        copies.clear()
        drawing.clear()
        RecolouredTextures.forget()
    }

    /** [renderType], or its recoloured copy where the creature being submitted has a colour and it shows its texture. */
    @JvmStatic
    fun swapped(renderType: RenderType): RenderType {
        val colour = drawing.lastOrNull() ?: return renderType
        val ofThisColour = copies.getOrPut(colour.packed()) { WeakHashMap() }
        return ofThisColour.getOrPut(renderType) { recolouredCopyOf(renderType, colour) ?: renderType }
    }

    private fun colourFor(type: EntityType<*>): Rgba? {
        val level = Minecraft.getInstance().level ?: return null
        val creatures = LevelLooks.of(level.dimension())?.creatures ?: return null
        if (creatures.isNone) return null
        return creatures.of(BuiltInRegistries.ENTITY_TYPE.getKey(type))
    }

    /** [original] rebuilt through the public builder with its creature's own textures recoloured, or null where it has none. */
    private fun recolouredCopyOf(original: RenderType, colour: Rgba): RenderType? {
        val setup = original.state
        val textures = setup.textures.toList()
        if (textures.none { (_, binding) -> isACreaturesOwn(binding.location()) }) return null
        val builder = RenderSetup.builder(setup.pipeline)
        for ((name, binding) in textures) {
            val location = binding.location()
            val drawn = if (isACreaturesOwn(location)) RecolouredTextures.of(location, colour) else location
            // The binding's own supplier handed back as it was; vanilla declares its sampler nullable on one side only.
            @Suppress("UNCHECKED_CAST")
            builder.withTexture(name, drawn, binding.sampler() as java.util.function.Supplier<GpuSampler>)
        }
        if (setup.useLightmap) builder.useLightmap()
        if (setup.useOverlay) builder.useOverlay()
        if (setup.affectsCrumbling) builder.affectsCrumbling()
        if (setup.sortOnUpload) builder.sortOnUpload()
        if (setup.forceSolidModelPhase) builder.withForcedSolidModelPhase()
        setup.oitPipelineSet?.let(builder::setOitPipelines)
        builder.setLayeringTransform(setup.layeringTransform)
        builder.setTextureTransform(setup.textureTransform)
        val outlineName = setup.outlineTextureName
        if (outlineName != null) builder.setOutline(setup.outlineProperty, outlineName) else builder.setOutline(setup.outlineProperty)
        return RenderType.create("$NAME_PREFIX%08x".format(colour.packed()), builder.createRenderSetup())
    }

    private fun isACreaturesOwn(texture: Identifier): Boolean =
        texture.path.startsWith(CREATURE_TEXTURES) && !texture.path.startsWith(WORN_TEXTURES)

    private const val CREATURE_TEXTURES = "textures/entity/"
    private const val WORN_TEXTURES = "textures/entity/equipment/"
    private const val NAME_PREFIX = "ephemeris_recoloured_"
}
