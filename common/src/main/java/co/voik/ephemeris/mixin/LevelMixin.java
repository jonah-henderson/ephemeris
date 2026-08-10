package co.voik.ephemeris.mixin;

import co.voik.ephemeris.sky.LevelDaylight;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level's own suns decide how dark it is.
 *
 * <p><b>One field, and everything follows.</b> {@code updateSkyBrightness} recomputes {@code skyDarken} each
 * tick from the sky-light attribute, and every consequence of day and night reads that field:
 * {@code isBrightOutside} and {@code isDarkOutside}, and through them hostile spawning, phantoms, sleeping,
 * daylight sensors, villager schedules, and the block lighting itself. Writing it after vanilla has is the
 * whole of making a strange sky mean something, and is why there is no Mixin on any of the rest.
 *
 * <p><b>No loader event carries this.</b> Neither Fabric nor NeoForge offers a hook on sky brightness, and
 * the alternatives are worse in the same way: overriding the attribute would need a layer that varies with
 * time, and layers are built once when the level opens.
 *
 * <p>The decision itself is deliberately not here — see {@code LevelDaylight}, which leaves vanilla's own
 * curve alone unless the sky is one vanilla could not have drawn.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Shadow
    private int skyDarken;

    @Inject(method = "updateSkyBrightness", at = @At("TAIL"))
    private void ephemeris$letTheSunsDecide(CallbackInfo callback) {
        Integer darkening = LevelDaylight.INSTANCE.skyDarkenFor((Level) (Object) this);
        if (darkening != null) {
            this.skyDarken = darkening;
        }
    }
}
