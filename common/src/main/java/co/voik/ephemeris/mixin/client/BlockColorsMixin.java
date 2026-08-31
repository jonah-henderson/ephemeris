package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.GroundTints;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a level's leaves follow it, whichever renderer is drawing them.
 *
 * <p><b>The seam is the tint <i>source</i>, not the renderer that asks for one.</b> Two earlier attempts
 * hooked the block renderer and both failed silently on Fabric, because Fabric API's Indigo ships
 * {@code AltModelBlockRendererImpl} — its own copy of {@code ModelBlockRenderer}, with its own
 * {@code getTintColor} — and replaces vanilla's for terrain. Anything hooked on vanilla's renderer is
 * simply not called (Jonah, 2026-08-30, walked three times).
 *
 * <p>What both renderers do share is this: each asks {@code BlockColors.getTintSources} for the sources and
 * then calls {@code colorInWorld} on them. So the source is what gets wrapped, and every renderer that
 * reads {@code BlockColors} — vanilla, Indigo, and anything else built the same way — gets a leaf that asks
 * the level. That is also why grass never had this problem: it resolves through
 * {@code ClientLevel.getBlockTint}, which every renderer calls into rather than reimplementing.
 *
 * <p>Only leaves that already have a source are wrapped, so no list changes length. Cherry, pale oak and
 * azalea have no tint source at all and stay exactly as vanilla draws them.
 */
@Mixin(BlockColors.class)
public abstract class BlockColorsMixin {

    @Inject(method = "getTintSources", at = @At("RETURN"), cancellable = true)
    private void ephemeris$leavesFollowTheLevel(
            BlockState state, CallbackInfoReturnable<List<BlockTintSource>> sources) {
        sources.setReturnValue(GroundTints.INSTANCE.leavesFollowing(state, sources.getReturnValue()));
    }
}
