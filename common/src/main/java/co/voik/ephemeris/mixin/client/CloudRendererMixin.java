package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.Blaze3dSkyCanvas;
import co.voik.ephemeris.client.CloudMoment;
import co.voik.ephemeris.client.LevelRendering;
import co.voik.ephemeris.client.OffscreenLevelRender;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level draw its own overcast instead of vanilla's one drifting sheet.
 *
 * <p><b>Two injectors for one draw, because 26.3 split what a cloud layer is from when it is drawn.</b>
 * {@code prepare} carries every number — the colour, the height, the range, where the eye is and what time
 * it is — and builds the mesh; {@code render} is handed a {@code RenderPass} by the transparency pass and
 * issues the draw. Neither half is enough on its own: the first has the frame's facts and nowhere to put
 * them, the second has somewhere to draw and none of the facts.
 *
 * <p>So the facts are taken as they go past and used when the draw comes. Kept per renderer rather than
 * statically: a level rendered off-screen has a {@code CloudRenderer} of its own and must not read the
 * window's numbers.
 *
 * <p><b>The pass is borrowed rather than opened, and only here.</b> Everything else this library draws
 * happens where no pass is open, so the canvas opens its own; clouds are drawn inside one already, and a
 * pass cannot be opened inside a pass. {@link Blaze3dSkyCanvas#lending} hands vanilla's down for the length
 * of the draw and takes it back after.
 *
 * <p><b>Both transparency paths, because exactly one of them runs.</b> A client with "improved
 * transparency" on draws its clouds through {@code renderOit} instead, and `LevelRenderer` picks between
 * them per frame. {@code renderOit} is handed no pass — it makes its own through
 * {@code OitRenderPassProvider} — so there the canvas opens its own as it does everywhere else, and
 * nothing is lent.
 *
 * <p><b>It is called once per {@code OitStage}, and the deck is drawn on one of them.</b> Vanilla's clouds
 * are suppressed on all three; ours are drawn on {@code ACCUMULATE}, which is the stage where colour is
 * actually gathered and so the nearest thing to where vanilla's would have contributed. Drawn on every
 * stage it would be drawn three times a frame.
 *
 * <p><b>What that does not do is take part in the accumulation.</b> Our deck is drawn in a pass of its own
 * with an ordinary blended pipeline, so against other transparent things it orders as it always did rather
 * than order-independently. Giving the deck an {@code OitPipelineSet} of its own is what would close that,
 * and it is shader work; see {@code notes/drawing-in-26-3.md}.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    private static final String PREPARE = "prepare(ILnet/minecraft/client/CloudStatus;FI"
        + "Lnet/minecraft/world/phys/Vec3;JF)V";
    private static final String RENDER = "render(Lnet/minecraft/client/CloudStatus;"
        + "Lcom/mojang/renderpearl/api/commands/RenderPass;)V";
    private static final String RENDER_OIT = "renderOit(Lnet/minecraft/client/CloudStatus;"
        + "Lnet/minecraft/client/renderer/oit/OitStage;"
        + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
        + "Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;)V";

    @Unique
    private int ephemeris$color;
    @Unique
    private float ephemeris$bottomY;
    @Unique
    private int ephemeris$range;
    @Unique
    private Vec3 ephemeris$cameraPosition;
    @Unique
    private long ephemeris$gameTime;
    @Unique
    private float ephemeris$partialTicks;

    /** The frame's numbers, taken as they go past. Nothing is drawn here: it is the wrong end of the frame. */
    @Inject(method = PREPARE, at = @At("HEAD"))
    private void ephemeris$rememberTheFrame(
            int color, CloudStatus cloudStatus, float bottomY, int range,
            Vec3 cameraPosition, long gameTime, float partialTicks, CallbackInfo callback) {
        this.ephemeris$color = color;
        this.ephemeris$bottomY = bottomY;
        this.ephemeris$range = range;
        this.ephemeris$cameraPosition = cameraPosition;
        this.ephemeris$gameTime = gameTime;
        this.ephemeris$partialTicks = partialTicks;
        // **And the uploads, here rather than at the draw.** Inside a pass a renderer may issue pass
        // commands and nothing else — no texture load, no buffer map, no ring-buffer rotation — and the
        // draw is inside one. See LevelCloudRenderer.ready.
        CloudMoment moment = ephemeris$momentOf(cloudStatus);
        if (moment != null) {
            LevelRendering.INSTANCE.readyClouds(moment);
        }
    }

    @Inject(method = RENDER, at = @At("HEAD"), cancellable = true)
    private void ephemeris$drawTheLevelsClouds(
            CloudStatus cloudStatus, RenderPass pass, CallbackInfo callback) {
        // No prepare this frame means no numbers to draw with, and vanilla will not have drawn either.
        CloudMoment moment = ephemeris$momentOf(cloudStatus);
        if (moment == null) {
            return;
        }
        if (Blaze3dSkyCanvas.INSTANCE.lending(pass, () -> LevelRendering.INSTANCE.drawClouds(moment))) {
            callback.cancel();
        }
    }

    /**
     * The same again where the client sorts its transparency the other way — drawn on one stage, suppressed
     * on all of them.
     */
    @Inject(method = RENDER_OIT, at = @At("HEAD"), cancellable = true)
    private void ephemeris$drawTheLevelsCloudsInOit(
            CloudStatus cloudStatus, OitStage stage, GpuTextureView depth,
            OitRenderPassProvider.Parameters parameters, CallbackInfo callback) {
        CloudMoment moment = ephemeris$momentOf(cloudStatus);
        if (moment == null) {
            return;
        }
        // Asked on every stage so that a level which claims its clouds has vanilla's silenced on all of
        // them, and drawn on the one that gathers colour.
        boolean ours = stage == OitStage.ACCUMULATE
                ? LevelRendering.INSTANCE.drawClouds(moment)
                : LevelRendering.INSTANCE.claimsClouds(moment);
        if (ours) {
            callback.cancel();
        }
    }

    /** The frame's numbers as a moment, or null where no prepare has given us any. */
    @Unique
    private CloudMoment ephemeris$momentOf(CloudStatus cloudStatus) {
        ClientLevel level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        if (level == null || this.ephemeris$cameraPosition == null) {
            return null;
        }
        return new CloudMoment(
                level,
                OffscreenLevelRender.INSTANCE.cloudTargetBeingDrawnOnto(),
                OffscreenLevelRender.INSTANCE.cameraBeingDrawnFrom(),
                this.ephemeris$color,
                cloudStatus,
                this.ephemeris$bottomY,
                this.ephemeris$range,
                this.ephemeris$cameraPosition,
                this.ephemeris$gameTime,
                this.ephemeris$partialTicks);
    }
}
