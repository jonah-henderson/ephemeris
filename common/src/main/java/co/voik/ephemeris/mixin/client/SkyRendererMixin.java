package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.LevelRendering;
import co.voik.ephemeris.client.SkyMoment;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level draw suns, moons and stars of its own.
 *
 * <p><b>A Mixin because on 26.1 there is nothing else.</b> {@code DimensionSpecialEffects} no longer exists,
 * Fabric API dropped {@code DimensionRenderingRegistry}, and a dimension type cannot reach the client to
 * carry this. Every mod wanting a sky of its own writes this same Mixin, and two that do fight over one
 * seam — which is the case for it living in a library instead.
 *
 * <p>The seam is one method wide and it is the method named for exactly what is being taken over. When no
 * renderer claims the level, this does not cancel, so an ordinary world runs vanilla's own code rather than
 * an imitation of it.
 *
 * <p>The {@code poseStack} is not forwarded: vanilla is handed a fresh one here and pushes its transform
 * onto {@code RenderSystem.getModelViewStack()} anyway, which is where a renderer should write.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void ephemeris$drawTheLevelsSky(
            PoseStack poseStack,
            float sunAngle,
            float moonAngle,
            float starAngle,
            MoonPhase moonPhase,
            float rainBrightness,
            float starBrightness,
            CallbackInfo callback) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        var moment = new SkyMoment(level, sunAngle, moonAngle, starAngle, moonPhase, rainBrightness, starBrightness);
        if (LevelRendering.INSTANCE.drawSky(moment)) {
            callback.cancel();
        }
    }
}
