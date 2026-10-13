package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.LevelRain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level's rain fall at its own pace ({@code RainLook.pace}).
 *
 * <p>A rain column's fall is its texture scrolled by game time times a speed drawn per column, in the private
 * {@code createRainColumnInstance}, which is handed no level. So the level's pace is read once as the columns
 * are extracted, and every column's speed scaled by it. Neither loader has a weather-render event.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    @Unique
    private float ephemeris$pace = 1.0f;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void ephemeris$readThePace(
            ClientLevel level, float partialTicks, Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo info) {
        this.ephemeris$pace = LevelRain.of(level).getPace();
    }

    /** The column's speed: the second float local, after {@code partialTicks}. */
    @ModifyVariable(method = "createRainColumnInstance", at = @At("STORE"), ordinal = 1)
    private float ephemeris$slowTheColumn(float speed) {
        return speed * this.ephemeris$pace;
    }
}
