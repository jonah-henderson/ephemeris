package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.GroundTints;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a level's leaves follow it even where vanilla nailed their colour down.
 *
 * <p><b>Two leaves in the game never ask what biome they are in.</b> {@code BlockColors} registers spruce
 * and birch with {@code BlockTintSources.constant(…)} — a fixed number — where every other leaf gets
 * {@code BlockTintSources.foliage()} and goes through the biome resolver. So a level that repaints its
 * foliage repainted all of them but those two, which read as the feature half-working rather than as two
 * blocks being special (Jonah, 2026-08-30, walked).
 *
 * <p>Nothing is wrong with {@code ClientLevelMixin}'s seam; these blocks simply never reach a resolver at
 * all. This is the one place a block's tint is resolved <i>in world</i>, which is where the question "what
 * does a leaf look like here" can actually be asked.
 *
 * <p><b>A wrap rather than a redirect</b>, so the original call is still made for every block this declines
 * to answer for — which is all of them but leaves, in every level that never mentioned foliage.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

    @WrapOperation(
            method = "computeTintColor",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/color/block/BlockTintSource;colorInWorld("
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                            + "Lnet/minecraft/core/BlockPos;)I"))
    private int ephemeris$leavesFollowTheLevel(
            BlockTintSource source,
            BlockState state,
            BlockAndTintGetter level,
            BlockPos pos,
            Operation<Integer> original) {
        return GroundTints.INSTANCE.leafTintOr(
                state, level, pos, () -> original.call(source, state, level, pos));
    }
}
