package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.RecolouredRenderTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says which creature is being submitted, for {@link RecolouredRenderTypes} to recolour what it draws.
 *
 * <p><b>Why a mixin.</b> This is the one call every entity is submitted through — the level's, a spawner's spinning
 * mob, an inventory screen's — and it submits each one whole, its layers included, before the next. Neither loader has
 * an event around an entity's whole submission: NeoForge's {@code RenderLivingEvent} fires inside it and only for the
 * living, Fabric has none.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("HEAD")
    )
    private void ephemeris$beginning(
        EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack pose, SubmitNodeCollector collector,
        CallbackInfo callback
    ) {
        RecolouredRenderTypes.beginning(state.entityType);
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("RETURN")
    )
    private void ephemeris$ending(
        EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack pose, SubmitNodeCollector collector,
        CallbackInfo callback
    ) {
        RecolouredRenderTypes.ending();
    }
}
