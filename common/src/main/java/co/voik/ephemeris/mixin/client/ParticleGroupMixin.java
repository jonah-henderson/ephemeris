package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.WindBorne;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the light particles along a level's wind ({@code WindBorne.carry}).
 *
 * <p>Here, after the engine has ticked one, rather than in {@code Particle.tick}: most light particles override
 * {@code tick} without calling the base, and this is the one call every particle passes through. Neither loader
 * has a particle-tick event.
 */
@Mixin(ParticleGroup.class)
public abstract class ParticleGroupMixin {

    @Inject(method = "tickParticle", at = @At("TAIL"))
    private void ephemeris$carryAlongTheWind(Particle particle, CallbackInfo info) {
        WindBorne.carry(particle, ((ParticleAccessor) particle).ephemeris$level());
    }
}
