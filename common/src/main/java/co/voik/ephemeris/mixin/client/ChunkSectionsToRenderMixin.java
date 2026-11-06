package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.TurningTerrain;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The rest of drawing turning terrain ({@link TurningTerrain}): the turns bound to the pass, and the water drawn through
 * our copies of the transparency stages when "improved transparency" draws it through {@code renderOit} instead.
 *
 * <p><b>Why a mixin.</b> {@code renderLayers} is private and is where every terrain pass binds its uniforms; neither
 * loader has an event inside it. {@code renderOit} names vanilla's transparency pipelines inline and hands them on as
 * the override, so that is the one place they can be exchanged.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderLayers", at = @At("HEAD"))
    private void ephemeris$bindTheTurns(
        ChunkSectionLayer[] layers,
        GpuSampler sampler,
        RenderPass renderPass,
        GpuTextureView atlas,
        GpuTextureView lightmap,
        @Nullable RenderPipeline renderPipelineOverride,
        @Nullable RenderPipeline renderPipelineOverrideMultidraw,
        CallbackInfo callback
    ) {
        RenderPipeline override = renderPipelineOverride != null ? renderPipelineOverride : renderPipelineOverrideMultidraw;
        TurningTerrain.bindTo(renderPass, layers, override);
    }

    /** The transparency stage {@code renderOit} is drawing, for the two below; the render thread's alone. */
    @Unique
    private OitStage ephemeris$stage = OitStage.ACCUMULATE;

    @Inject(method = "renderOit", at = @At("HEAD"))
    private void ephemeris$rememberTheStage(
        GpuSampler sampler, OitStage stage, OitRenderPassProvider.Parameters params, GpuTextureView atlas, GpuTextureView lightmap,
        CallbackInfo callback
    ) {
        ephemeris$stage = stage;
    }

    @ModifyArg(method = "renderOit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderLayers([Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V"), index = 5)
    private RenderPipeline ephemeris$turningWater(RenderPipeline vanilla) {
        return TurningTerrain.oitPipelineFor(vanilla, ephemeris$stage, false);
    }

    @ModifyArg(method = "renderOit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderLayers([Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V"), index = 6)
    private RenderPipeline ephemeris$turningWaterMultidraw(RenderPipeline vanilla) {
        return TurningTerrain.oitPipelineFor(vanilla, ephemeris$stage, true);
    }
}
