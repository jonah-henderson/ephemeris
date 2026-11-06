package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.RecolouredRenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A creature's model drawn in a recoloured copy of its render type ({@link RecolouredRenderTypes}).
 *
 * <p><b>Why a mixin.</b> Every model an entity submits — its body, each layer, a beam — funnels into these two calls
 * with the render type already chosen, and vanilla only reads it back when it draws. Swapping it here reaches the render
 * types a renderer built once when its class loaded (a spider's eyes), which no hook where a texture is named can.
 * Neither loader has a submission event.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {

    @ModifyVariable(method = "submitModel", at = @At("HEAD"), argsOnly = true)
    private RenderType ephemeris$recolouredModel(RenderType renderType) {
        return RecolouredRenderTypes.swapped(renderType);
    }

    @ModifyVariable(method = "submitCustomGeometry", at = @At("HEAD"), argsOnly = true)
    private RenderType ephemeris$recolouredGeometry(RenderType renderType) {
        return RecolouredRenderTypes.swapped(renderType);
    }
}
