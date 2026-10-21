package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.OffscreenLightmap;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lights a level drawn off-screen by its own lightmap ({@code OffscreenLightmap}) rather than the player's.
 *
 * <p>The lightmap is a private final field of {@code GameRenderer} with no setter, read by every renderer through
 * these two getters; neither loader has an event for it. Outside an off-screen scope both answer as vanilla does.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = {"lightmap", "levelLightmap"}, at = @At("HEAD"), cancellable = true)
    private void ephemeris$lightTheLevelBeingDrawn(CallbackInfoReturnable<GpuTextureView> callback) {
        GpuTextureView offscreen = OffscreenLightmap.INSTANCE.view();
        if (offscreen != null) {
            callback.setReturnValue(offscreen);
        }
    }
}
