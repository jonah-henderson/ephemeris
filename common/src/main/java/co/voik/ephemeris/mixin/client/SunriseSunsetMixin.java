package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.HorizonMoment;
import co.voik.ephemeris.client.LevelRendering;
import co.voik.ephemeris.client.OffscreenLevelRender;
import co.voik.ephemeris.client.SkyThroughFog;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level paint its own sunrises and sunsets.
 *
 * <p><b>A second seam because vanilla draws the glow separately from the bodies</b>, and neither half of
 * what it does survives a sky with suns of its own:
 *
 * <ul>
 *   <li>The <b>colour and its alpha</b> come from {@code EnvironmentAttributes.SUNRISE_SUNSET_COLOR}, which
 *       the overworld timeline keyframes against the world clock. So the glow happens at vanilla's sunrise
 *       times whatever a level's own suns are doing.
 *   <li>The <b>position is a coin toss</b>: {@code Mth.sin(sunAngle) < 0 ? 180 : 0}. East or west, and
 *       nothing between — not even the real bearing of vanilla's own sun.
 * </ul>
 *
 * <p>So a level whose sun rises in the north, or has two suns, or has one that never sets, gets a glow at
 * the wrong time in one of two places. Taking the call over is the only way to fix either, and taking it
 * over gets the other for free.
 *
 * <p>When no renderer claims the level this does not cancel, so an ordinary world keeps vanilla's own
 * keyframed glow exactly — which is better than a reimplementation of it.
 */
@Mixin(SkyRenderer.class)
public class SunriseSunsetMixin {

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void ephemeris$paintTheLevelsHorizon(
            PoseStack poseStack, float sunAngle, int sunriseAndSunsetColor, CallbackInfo callback) {
        var camera = OffscreenLevelRender.INSTANCE.cameraBeingDrawnFrom();
        // The fan's lit vertex stands where the bodies do, so the same fog that hides them hides it — and
        // it is the half of the sky that reads worst when it leaks, being a hundred degrees wide.
        if (SkyThroughFog.INSTANCE.hidesTheSky(camera)) {
            callback.cancel();
            return;
        }
        var level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        if (level == null) {
            return;
        }
        if (LevelRendering.INSTANCE.drawHorizon(new HorizonMoment(
                level,
                OffscreenLevelRender.INSTANCE.targetBeingDrawnOnto(),
                camera,
                sunAngle,
                sunriseAndSunsetColor))) {
            callback.cancel();
        }
    }
}
