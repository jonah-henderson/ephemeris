package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.CloudMoment;
import co.voik.ephemeris.client.LevelRendering;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level draw its own overcast instead of vanilla's one drifting sheet.
 *
 * <p>The second of the two seams, the same shape as {@link SkyRendererMixin}: one method, at head,
 * cancelling only when something claims the level.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ephemeris$drawTheLevelsClouds(
            int color,
            CloudStatus cloudStatus,
            float bottomY,
            int range,
            Vec3 cameraPosition,
            long gameTime,
            float partialTicks,
            CallbackInfo callback) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        var moment = new CloudMoment(
                level, color, cloudStatus, bottomY, range, cameraPosition, gameTime + partialTicks);
        if (LevelRendering.INSTANCE.drawClouds(moment)) {
            callback.cancel();
        }
    }
}
