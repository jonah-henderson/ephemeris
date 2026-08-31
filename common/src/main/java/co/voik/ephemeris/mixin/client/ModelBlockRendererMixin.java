package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.GroundTints;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a level's leaves follow it even where vanilla nailed their colour down, or left it out.
 *
 * <p><b>Vanilla's leaves are three different things and only one of them asks a biome anything.</b> Oak and
 * its kin are registered with {@code BlockTintSources.foliage()} and go through the colour resolver. Spruce
 * and birch carry a {@code constant} that never asks. Cherry, pale oak and azalea carry <i>no tint source
 * at all</i>, so nothing is asked and nothing is applied.
 *
 * <p>So the hook is on {@code getTintColor} — where the renderer decides what a tinted quad is multiplied
 * by — and not on the tint source's own call, which the third kind never reaches. That was the fault in the
 * first attempt: it could only ever have caught two of the three (Jonah, 2026-08-30, walked twice).
 *
 * <p><b>A plain injection, deliberately.</b> The composable one silently does nothing where MixinExtras has
 * not initialised, and a hook that fails by having no effect is indistinguishable from the fault it was
 * written to fix. This one applies or the game does not start, which is the right way round for something
 * whose only symptom is a leaf being the wrong colour.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

    @Inject(method = "getTintColor", at = @At("HEAD"), cancellable = true)
    private void ephemeris$leavesFollowTheLevel(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            int tintIndex,
            CallbackInfoReturnable<Integer> tint) {
        Integer painted = GroundTints.INSTANCE.leafTintIn(state, level, pos);
        if (painted != null) {
            tint.setReturnValue(painted);
        }
    }
}
