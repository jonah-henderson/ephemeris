package co.voik.ephemeris.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The level a particle is in, which it keeps to itself, for {@code ParticleGroupMixin}; and how many ticks it has
 * lived and how fast it rises, which {@code WindBorne} flies its debris by.
 */
@Mixin(Particle.class)
public interface ParticleAccessor {

    @Accessor("level")
    ClientLevel ephemeris$level();

    @Accessor("age")
    int ephemeris$age();

    @Accessor("yd")
    void ephemeris$setRising(double rising);
}
