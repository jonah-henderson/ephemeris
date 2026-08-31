package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.GroundTints;
import co.voik.ephemeris.client.LevelRendering;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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
 *
 * <p>It also lets a level repaint what grows in it. That is a second seam and a different shape — see
 * {@link #ephemeris$tintTheGround}.
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

    /**
     * Lets a level repaint its own grass, leaves and litter.
     *
     * <p><b>The argument rather than the result, which is what makes this one line instead of twenty.</b>
     * {@code ColorResolver} is handed a biome and a position and knows nothing about which level it is in,
     * so nothing can be decided there; {@code calculateBlockTint} has the level, and then hands the resolver
     * to its own blending loop. Swapping the resolver on the way in means <i>vanilla's</i> loop runs, over
     * ours — so the biome-blend radius, the averaging, and the boundary between a repainted biome and an
     * ordinary one all keep working with nothing written for them.
     *
     * <p>Wrapping the result instead would have meant re-implementing that loop and keeping it in step
     * forever; wrapping the inner call would have fired per sample and still not carried the level.
     *
     * <p>Once per cache miss, never per block — {@code ClientLevel} keeps a {@code BlockTintCache} per
     * resolver, and it is keyed on the resolver vanilla passed rather than on what this returns.
     */
    @ModifyVariable(method = "calculateBlockTint", at = @At("HEAD"), argsOnly = true)
    private ColorResolver ephemeris$tintTheGround(ColorResolver resolver) {
        return GroundTints.INSTANCE.wrap((ClientLevel) (Object) this, resolver);
    }
}
