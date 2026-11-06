package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.TurningTerrain;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A layer of the chunk mesh drawn through {@link TurningTerrain}'s copy of its pipeline, where the level being drawn
 * turns that layer's colours.
 *
 * <p><b>Why a mixin.</b> A layer's pipeline is fixed in the enum's constructor and asked for by
 * {@code ChunkSectionsToRender.render} with no override unless the wireframe debug view is on; neither loader has an
 * event for which pipeline terrain is drawn through. An override handed to {@code renderLayers} would be one pipeline
 * for every layer of a group, where the solid and cutout layers need one each.
 */
@Mixin(ChunkSectionLayer.class)
public abstract class ChunkSectionLayerMixin {

    @Inject(method = "pipeline", at = @At("HEAD"), cancellable = true)
    private void ephemeris$turning(boolean multiDraw, CallbackInfoReturnable<RenderPipeline> callback) {
        RenderPipeline turning = TurningTerrain.pipelineFor((ChunkSectionLayer) (Object) this, multiDraw);
        if (turning != null) callback.setReturnValue(turning);
    }
}
