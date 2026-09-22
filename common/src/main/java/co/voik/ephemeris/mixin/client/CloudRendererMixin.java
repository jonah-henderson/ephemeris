package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.Blaze3dSkyCanvas;
import co.voik.ephemeris.client.CloudMoment;
import co.voik.ephemeris.client.LevelRendering;
import co.voik.ephemeris.client.OffscreenLevelRender;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
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
 * <p><b>The order-independent path is not served yet.</b> A client with OIT on draws its clouds through
 * {@code renderOit}, which is handed no pass at all, and this does not touch it — such a client gets
 * vanilla's clouds where a level asked for its own. Vanilla's sheet is the less wrong of the two answers
 * available, the other being no clouds at all; see {@code notes/drawing-in-26-3.md}.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    private static final String PREPARE = "prepare(ILnet/minecraft/client/CloudStatus;FI"
        + "Lnet/minecraft/world/phys/Vec3;JF)V";
    private static final String RENDER = "render(Lnet/minecraft/client/CloudStatus;"
        + "Lcom/mojang/renderpearl/api/commands/RenderPass;)V";

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
    }

    @Inject(method = RENDER, at = @At("HEAD"), cancellable = true)
    private void ephemeris$drawTheLevelsClouds(
            CloudStatus cloudStatus, RenderPass pass, CallbackInfo callback) {
        ClientLevel level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        // No prepare this frame means no numbers to draw with, and vanilla will not have drawn either.
        if (level == null || this.ephemeris$cameraPosition == null) {
            return;
        }
        CloudMoment moment = new CloudMoment(
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
        if (Blaze3dSkyCanvas.INSTANCE.lending(pass, () -> LevelRendering.INSTANCE.drawClouds(moment))) {
            callback.cancel();
        }
    }
}
