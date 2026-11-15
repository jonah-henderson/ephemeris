package co.voik.ephemeris.client

import net.minecraft.client.model.EntityModel
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.EnergySwirlLayer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which creatures are drawn wrapped in an energy swirl, as a charged creeper is. A mod says which ([drawWhere]), an
 * entity at a time; every living creature's renderer carries the layer (`LivingEntityRendererMixin`), and its render
 * state is told whether its entity swirls as it is extracted.
 *
 * The swirl is drawn over the creature's own model, where vanilla's creeper has an inflated copy of its own.
 */
object EntitySwirls {

    private val askers = CopyOnWriteArrayList<(Entity) -> Boolean>()

    /** Draws a swirl on every creature [isSwirling] answers for, beside whatever else asked. */
    fun drawWhere(isSwirling: (Entity) -> Boolean) {
        askers += isSwirling
    }

    @JvmStatic
    fun isSwirling(entity: Entity): Boolean = askers.any { it(entity) }

    /** The swirl, as a layer of [parent]'s. */
    @JvmStatic
    fun <S : LivingEntityRenderState, M : EntityModel<S>> layerFor(parent: RenderLayerParent<S, M>): RenderLayer<S, M> =
        SwirlLayer(parent)

    private class SwirlLayer<S : LivingEntityRenderState, M : EntityModel<S>>(parent: RenderLayerParent<S, M>) :
        EnergySwirlLayer<S, M>(parent) {

        override fun isPowered(state: S): Boolean = (state as SwirlingRenderState).ephemerisSwirling

        override fun xOffset(time: Float): Float = time * DRIFT

        override fun getTextureLocation(): Identifier = TEXTURE

        override fun model(): M = parentModel
    }

    private val TEXTURE = Identifier.withDefaultNamespace("textures/entity/creeper/creeper_armor.png")

    /** How fast the swirl drifts, as a charged creeper's does. */
    private const val DRIFT = 0.01f
}

/** A creature's render state, told whether to draw it swirling ([EntitySwirls]). */
interface SwirlingRenderState {
    var ephemerisSwirling: Boolean
}
