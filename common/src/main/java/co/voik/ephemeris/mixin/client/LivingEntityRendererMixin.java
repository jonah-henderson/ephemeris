package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.EntitySwirls;
import co.voik.ephemeris.client.SwirlingRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every living creature's renderer carries {@link EntitySwirls}' layer, and tells each render state it extracts whether
 * its entity swirls.
 *
 * <p><b>Why a mixin.</b> A layer for every creature and a flag carried from the entity to its render state are each a
 * loader's own: NeoForge adds layers by event and carries render data by its own modifiers, while Fabric adds layers by
 * event but has no hook into extraction at all. The constructor and {@code extractRenderState} are the one place both
 * happen for every living renderer, a player's and a modded creature's alike.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {

    @Shadow
    protected abstract boolean addLayer(RenderLayer<S, M> layer);

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "<init>", at = @At("TAIL"))
    private void ephemeris$addSwirl(EntityRendererProvider.Context context, M model, float shadowRadius, CallbackInfo callback) {
        addLayer((RenderLayer) EntitySwirls.layerFor((RenderLayerParent) this));
    }

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void ephemeris$extractSwirling(T entity, S state, float partialTick, CallbackInfo callback) {
        ((SwirlingRenderState) state).setEphemerisSwirling(EntitySwirls.isSwirling(entity));
    }
}
