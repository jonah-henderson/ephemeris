package co.voik.ephemeris.mixin;

import co.voik.ephemeris.LevelWeather;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a level keep weather of its own — see {@link LevelWeather} for what that buys and why it is needed.
 *
 * <p><b>Why a Mixin.</b> {@code setRainLevel} and {@code setThunderLevel} are public, so the <i>state</i>
 * could be overwritten every tick without one — but that fights {@code advanceWeatherCycle} on every tick,
 * sends two packets where one would do, and still cannot own the timers, so the natural cycle and
 * {@code /weather} would stay global. No loader event carries which weather data a level uses, and a Mixin
 * on {@code MinecraftServer.getWeatherData} would be broader and would not know the calling level. This
 * method is the narrowest seam there is: two classes in the game read it.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "getWeatherData", at = @At("HEAD"), cancellable = true)
    private void ephemeris$ownWeather(CallbackInfoReturnable<WeatherData> callback) {
        WeatherData own = LevelWeather.INSTANCE.of((ServerLevel) (Object) this);
        if (own != null) {
            callback.setReturnValue(own);
        }
    }
}
