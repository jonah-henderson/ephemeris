package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.LevelRendering;
import co.voik.ephemeris.client.OffscreenLevelRender;
import co.voik.ephemeris.client.SkyMoment;
import co.voik.ephemeris.client.SkyThroughFog;
import com.mojang.blaze3d.vertex.PoseStack;
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
 *
 * <p><b>Two injectors, because an overlay must run over whichever sky was drawn.</b> Cancelling from the
 * head returns at the injection point, so a {@code TAIL} injector runs exactly when the head did not
 * cancel — which is to say exactly when vanilla drew the sky itself. Each path therefore draws the
 * overlays once and draws them last. A flag forwarded from the head would have said the same thing and
 * left a field on a Mixin to reason about.
 *
 * <p><b>Both paths are declined outright where the fog has closed in front of the sky</b> — see
 * {@link co.voik.ephemeris.client.SkyThroughFog}. Cancelling from the head takes the overlays with it,
 * which is the intent: what cannot be seen is not drawn, whoever was going to draw it.
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
        var camera = OffscreenLevelRender.INSTANCE.cameraBeingDrawnFrom();
        // Nothing of the sky could be seen from in here, so nothing of it is drawn — vanilla's bodies, a
        // level's own, and every overlay over them alike. See SkyThroughFog for why the fog cannot say
        // this for itself.
        if (SkyThroughFog.INSTANCE.hidesTheSky(camera)) {
            callback.cancel();
            return;
        }
        var level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        if (level == null) {
            return;
        }
        var moment = new SkyMoment(
                level,
                OffscreenLevelRender.INSTANCE.targetBeingDrawnOnto(),
                camera,
                sunAngle,
                moonAngle,
                starAngle,
                moonPhase,
                rainBrightness,
                starBrightness);
        if (LevelRendering.INSTANCE.drawSky(moment)) {
            LevelRendering.INSTANCE.drawSkyOverlays(moment);
            callback.cancel();
        }
    }

    /** Reached only where nothing claimed the sky above, so vanilla has just drawn its own. */
    @Inject(method = "renderSunMoonAndStars", at = @At("TAIL"))
    private void ephemeris$drawOverVanillasSky(
            PoseStack poseStack,
            float sunAngle,
            float moonAngle,
            float starAngle,
            MoonPhase moonPhase,
            float rainBrightness,
            float starBrightness,
            CallbackInfo callback) {
        var level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        if (level == null) {
            return;
        }
        LevelRendering.INSTANCE.drawSkyOverlays(
                new SkyMoment(
                        level,
                        OffscreenLevelRender.INSTANCE.targetBeingDrawnOnto(),
                        OffscreenLevelRender.INSTANCE.cameraBeingDrawnFrom(),
                        sunAngle,
                        moonAngle,
                        starAngle,
                        moonPhase,
                        rainBrightness,
                        starBrightness));
    }
}
