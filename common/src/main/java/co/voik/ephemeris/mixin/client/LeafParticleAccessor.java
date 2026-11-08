package co.voik.ephemeris.mixin.client;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.block.UntintedParticleLeavesBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The leaf an untinted leaves block lets fall, which it keeps to itself, for {@code WindBorne}'s debris. */
@Mixin(UntintedParticleLeavesBlock.class)
public interface LeafParticleAccessor {

    @Accessor("leafParticle")
    ParticleOptions ephemeris$leafParticle();
}
