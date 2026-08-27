package co.voik.ephemeris.mixin;

import co.voik.ephemeris.LevelWeather;
import co.voik.ephemeris.RuntimeLevelSeeds;
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

    /**
     * The seed a runtime level generates from, where it has one of its own.
     *
     * <p><b>Everything that decides terrain reads this one method.</b> The chunk cache builds its
     * {@code RandomState} from it while this level's constructor is still running, and structure placement
     * asks it ever afterwards — so two levels sharing a world share their terrain unless something answers
     * differently. Vanilla has nowhere to put a second seed; NeoForge added {@code LevelStem}'s
     * {@code neoforge:seed_override} and Fabric has no equivalent, so reaching for that would compile
     * against the merged sources and fail on the loader that never had it.
     *
     * <p>Keyed by dimension because that is settled in {@code Level}'s own constructor, before the body
     * that asks. See {@code RuntimeLevelSeeds}.
     */
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void ephemeris$ownSeed(CallbackInfoReturnable<Long> callback) {
        Long own = RuntimeLevelSeeds.INSTANCE.of(((ServerLevel) (Object) this).dimension());
        if (own != null) {
            callback.setReturnValue(own);
        }
    }

    @Inject(method = "getWeatherData", at = @At("HEAD"), cancellable = true)
    private void ephemeris$ownWeather(CallbackInfoReturnable<WeatherData> callback) {
        WeatherData own = LevelWeather.INSTANCE.of((ServerLevel) (Object) this);
        if (own != null) {
            callback.setReturnValue(own);
        }
    }
}
