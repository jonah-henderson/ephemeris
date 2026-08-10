package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.LevelRendering;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a level bend its own air — fog, sky colour, light tint, and how far you can see.
 *
 * <p><b>The one thing a server cannot do for itself.</b> {@code ServerLevel.setEnvironmentAttributes} is
 * public, so every *gameplay* attribute is settled server-side; the visual half is read from
 * {@code ClientLevel}'s own {@code private final} system, built in its constructor from the dimension type
 * and the biomes. There is no setter and no event, and the value it builds is what every renderer asks.
 *
 * <p>So this joins the one seam there is: the private method that assembles the layers. Injecting at its
 * return leaves vanilla's whole stack intact underneath and adds anything registered on top, which is what
 * a level's own air is — a layer over the world it is standing in.
 *
 * <p>Unlike the sky, layers do not claim: every one registered is applied over the last. Attributes compose
 * by their nature, and a first-wins rule would make two mods that both tint the fog silently exclusive.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "addEnvironmentAttributeLayers", at = @At("RETURN"), cancellable = true)
    private void ephemeris$paintTheAir(
            EnvironmentAttributeSystem.Builder builder,
            CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> layers) {
        ClientLevel level = (ClientLevel) (Object) this;
        layers.setReturnValue(LevelRendering.INSTANCE.paintEnvironment(level, layers.getReturnValue()));
    }
}
