package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.Rgba;
import co.voik.ephemeris.client.Blaze3dSkyCanvas;
import co.voik.ephemeris.client.HorizonMoment;
import co.voik.ephemeris.client.LevelRendering;
import co.voik.ephemeris.client.OffscreenLevelRender;
import co.voik.ephemeris.client.SkyMoment;
import co.voik.ephemeris.client.SkyThroughFog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level draw a sky of its own — the whole of it.
 *
 * <p><b>A Mixin because there is nothing else.</b> {@code DimensionSpecialEffects} is gone, Fabric API
 * dropped {@code DimensionRenderingRegistry}, and a dimension type cannot reach the client to carry this.
 * Every mod wanting a sky of its own writes this same Mixin, and two that do fight over one seam — which is
 * the case for it living in a library instead.
 *
 * <p><b>One seam where 26.3 left three.</b> It used to be possible to take vanilla's bodies and leave its
 * sky disc, or take its sunrise and leave both, because each was its own method. 26.3 gave every one of
 * those a {@code RenderPass} created by {@code render} and handed down — and a pass cannot be opened inside
 * a pass, so none of them can be drawn from any more. {@code render} itself is the last point at which no
 * pass is open, so that is where the seam is, and it is all-or-nothing: cancelling takes the disc, the
 * sunrise, the bodies and the disc below the world together.
 *
 * <p><b>Which is why the decision is made before anything is drawn.</b> {@link LevelRendering#ownsTheSky}
 * answers it without drawing, because the background has to go down first and by the time a renderer has
 * claimed the sky it is too late to put anything under it.
 *
 * <p><b>The fog upload is not optional.</b> Vanilla's first line hands the frame's fog to the shaders, and
 * the sky disc is one flat colour whose whole gradient toward the horizon comes from it. Cancel without
 * doing the same and the sky comes out flat.
 *
 * <p><b>The order is vanilla's, and the disc below the world is last on purpose.</b> Dome, then the
 * horizon's light, then the bodies, then the underside — the sky pass writes no depth, so what is drawn
 * later covers what came before, and that last disc is what stops a sun that has set showing through the
 * ground.
 *
 * <p><b>Two injectors, because an overlay must run over whichever sky was drawn.</b> Cancelling from the
 * head returns at the injection point, so the {@code TAIL} injector runs exactly when the head did not
 * cancel — which is to say exactly when vanilla drew the sky itself.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    private static final String RENDER = "render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
        + "Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V";

    /** Vanilla's own colour for the disc below the world, which it does not offer a say in. */
    private static final Rgba UNDER_THE_WORLD = new Rgba(0.0f, 0.0f, 0.0f, 1.0f);

    @Inject(method = RENDER, at = @At("HEAD"), cancellable = true)
    private void ephemeris$drawTheLevelsSky(
            GpuBufferSlice fog, SkyRenderState state, CallbackInfo callback) {
        ClientLevel level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        if (level == null || !LevelRendering.INSTANCE.ownsTheSky(level)) {
            return;
        }
        RenderSystem.setShaderFog(fog);
        SkyMoment moment = ephemeris$momentOf(level, state);

        // Nothing of the sky could be seen from in here, so none of it is drawn — a level's bodies, its
        // horizon and every overlay over them alike. The dome still goes down, because vanilla's disc did:
        // what the fog hides is the things *in* the sky, not the colour of it. See SkyThroughFog for why
        // the fog cannot say this for itself.
        if (SkyThroughFog.INSTANCE.hidesTheSky(moment.getCamera())) {
            ephemeris$layTheSky(moment);
            callback.cancel();
            return;
        }

        ephemeris$layTheSky(moment);
        LevelRendering.INSTANCE.drawHorizon(new HorizonMoment(
                level,
                moment.getTarget(),
                moment.getCamera(),
                state.sunAngle,
                ARGB.colorFromVector4f(state.sunriseAndSunsetColor)));
        LevelRendering.INSTANCE.drawSky(moment);
        LevelRendering.INSTANCE.drawSkyOverlays(moment);
        if (moment.getUndersideShowing()) {
            Blaze3dSkyCanvas.INSTANCE.drawUnderside(UNDER_THE_WORLD);
        }
        callback.cancel();
    }

    /** Reached only where the sky was left to vanilla, so it has just drawn its own. */
    @Inject(method = RENDER, at = @At("TAIL"))
    private void ephemeris$drawOverVanillasSky(
            GpuBufferSlice fog, SkyRenderState state, CallbackInfo callback) {
        ClientLevel level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        if (level == null) {
            return;
        }
        LevelRendering.INSTANCE.drawSkyOverlays(ephemeris$momentOf(level, state));
    }

    private SkyMoment ephemeris$momentOf(ClientLevel level, SkyRenderState state) {
        return new SkyMoment(
                level,
                OffscreenLevelRender.INSTANCE.targetBeingDrawnOnto(),
                OffscreenLevelRender.INSTANCE.cameraBeingDrawnFrom(),
                ephemeris$partOfATickOn(),
                state.sunAngle,
                state.moonAngle,
                state.starAngle,
                state.moonPhase,
                state.rainBrightness,
                state.starBrightness,
                Rgba.Companion.of(state.skyColor),
                state.shouldRenderDarkDisc);
    }

    /** The sky's own colour, laid before anything is drawn on it. */
    private void ephemeris$layTheSky(SkyMoment moment) {
        Blaze3dSkyCanvas.INSTANCE.drawDome(moment.getSkyColour());
    }

    /**
     * How far into the tick this frame is. Frozen counts as a whole one, which is what vanilla's own
     * renderers interpolate with.
     */
    private static float ephemeris$partOfATickOn() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
}
