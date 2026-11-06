package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.Blaze3dSkyCanvas;
import co.voik.ephemeris.client.CloudCarry;
import co.voik.ephemeris.client.CloudMoment;
import co.voik.ephemeris.client.CloudPictures;
import co.voik.ephemeris.client.LevelRendering;
import co.voik.ephemeris.client.OffscreenLevelRender;
import co.voik.ephemeris.sky.CloudPattern;
import co.voik.ephemeris.sky.SkySpec;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Objects;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level draw its own overcast instead of vanilla's one drifting sheet, and drift vanilla's at a pace of its
 * own where it keeps vanilla's ({@code SkySpec.cloudPace}) and along its wind where it has one ({@code CloudCarry}).
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

    /** How many ticks vanilla's clouds take to drift one cell, which a whole texture's width of cells repeats on. */
    @Unique
    private static final double TICKS_PER_CELL = 400.0;

    @Shadow
    private CloudRenderer.@Nullable TextureData texture;

    @Shadow
    private boolean needsRebuild;

    /** Vanilla's own picture, as resources last loaded it, for a level with none of its own to go back to. */
    @Unique
    private CloudRenderer.@Nullable TextureData ephemeris$vanillas;

    /** The picture of ours this renderer holds now, or null where it holds vanilla's. */
    @Unique
    private @Nullable CloudPattern ephemeris$worn;

    @Inject(
        method = "apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("TAIL")
    )
    private void ephemeris$rememberVanillasPicture(CallbackInfo callback) {
        this.ephemeris$vanillas = this.texture;
        this.ephemeris$worn = null;
    }

    /**
     * The level's own picture in vanilla's place, or vanilla's back where it has none — a change only when the level
     * being drawn asks for another, since the mesh is rebuilt from it.
     */
    @Unique
    private void ephemeris$wearTheLevelsPicture() {
        CloudPattern wanted = CloudPictures.INSTANCE.pictureFor(OffscreenLevelRender.INSTANCE.levelBeingDrawn());
        if (Objects.equals(wanted, this.ephemeris$worn)) {
            return;
        }
        // A renderer drawing another level off-screen may never have been handed resources; what it holds is its own.
        if (this.ephemeris$worn == null) {
            this.ephemeris$vanillas = this.texture;
        }
        this.texture = wanted == null ? this.ephemeris$vanillas : CloudPictures.INSTANCE.of(wanted);
        this.ephemeris$worn = wanted;
        this.needsRebuild = true;
    }

    /** A cell's width, vanilla's twelve blocks times the level's own scale. */
    @ModifyConstant(method = PREPARE, constant = @Constant(floatValue = 12.0F))
    private float ephemeris$cellWidth(float vanillas) {
        return vanillas * CloudPictures.INSTANCE.cellScaleFor(OffscreenLevelRender.INSTANCE.levelBeingDrawn());
    }

    @ModifyConstant(method = PREPARE, constant = @Constant(doubleValue = 12.0))
    private double ephemeris$cellWidthInBlocks(double vanillas) {
        return vanillas * CloudPictures.INSTANCE.cellScaleFor(OffscreenLevelRender.INSTANCE.levelBeingDrawn());
    }

    /** A cell's thickness, vanilla's four blocks grown with the cell but less: a colossal cloud is broad, not a tower. */
    @ModifyConstant(method = PREPARE, constant = @Constant(floatValue = 4.0F))
    private float ephemeris$cellThickness(float vanillas) {
        return vanillas * CloudPictures.INSTANCE.thicknessScaleFor(OffscreenLevelRender.INSTANCE.levelBeingDrawn());
    }

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
    /** How far the wind has carried the clouds this frame, or null where the level's air is still. */
    @Unique
    private CloudCarry.@Nullable Carried ephemeris$carried;

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
        ephemeris$wearTheLevelsPicture();
        // **And the uploads, here rather than at the draw.** Inside a pass a renderer may issue pass
        // commands and nothing else — no texture load, no buffer map, no ring-buffer rotation — and the
        // draw is inside one. See LevelCloudRenderer.ready.
        CloudMoment moment = ephemeris$momentOf(cloudStatus);
        if (moment != null) {
            LevelRendering.INSTANCE.readyClouds(moment);
        }
    }

    /**
     * Vanilla's clouds drifted at the level's own pace. The offset is worked out again from the frame's time rather
     * than scaled, and wrapped where vanilla wraps it — one texture's width — so a pace that is no whole number does
     * not jump the sheet sideways each time the wrap comes round.
     */
    @ModifyVariable(method = PREPARE, at = @At("STORE"), name = "cloudOffset")
    private float ephemeris$driftAtTheLevelsPace(float cloudOffset) {
        float pace = LevelRendering.INSTANCE.cloudPace(OffscreenLevelRender.INSTANCE.levelBeingDrawn());
        CloudRenderer.TextureData drawn = this.texture;
        if (pace == SkySpec.ORDINARY_CLOUD_PACE || drawn == null) {
            return cloudOffset;
        }
        double period = drawn.width() * TICKS_PER_CELL;
        double ticks = ((this.ephemeris$gameTime % period) + this.ephemeris$partialTicks) * pace;
        return (float) (ticks % period);
    }

    /**
     * Vanilla's sheet carried along the level's wind. Vanilla reads its picture at the eye plus its drift along X and
     * a constant along Z, which moves the clouds west; where the level has a wind both are read upwind of the eye
     * instead, and the pace above has nothing left to act on.
     */
    @ModifyVariable(method = PREPARE, at = @At(value = "STORE", ordinal = 0), name = "cloudX")
    private double ephemeris$carryAlongTheWindX(double cloudX) {
        ClientLevel level = OffscreenLevelRender.INSTANCE.levelBeingDrawn();
        this.ephemeris$carried = level == null ? null : CloudCarry.INSTANCE.of(
                level,
                this.ephemeris$gameTime + (double) this.ephemeris$partialTicks,
                LevelRendering.INSTANCE.cloudPace(level));
        return this.ephemeris$carried == null ? cloudX : this.ephemeris$cameraPosition.x - this.ephemeris$carried.getX();
    }

    @ModifyVariable(method = PREPARE, at = @At(value = "STORE", ordinal = 0), name = "cloudZ")
    private double ephemeris$carryAlongTheWindZ(double cloudZ) {
        return this.ephemeris$carried == null ? cloudZ : cloudZ - this.ephemeris$carried.getZ();
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
