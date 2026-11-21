package co.voik.ephemeris.mixin;

import co.voik.ephemeris.RuntimeLevelSaving;
import net.minecraft.TracingExecutor;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Writes a runtime level's saved data on threads of its own — see {@link RuntimeLevelSaving} for why.
 *
 * <p><b>Why a Mixin.</b> Neither loader has an event around saved data being written, and the executor is fetched
 * inline. Both of {@code scheduleSave}'s writes take it inside a lambda, so the target is those lambdas by name;
 * {@code require = 2} fails the boot if either is no longer found.
 */
@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageMixin {

    @Redirect(
            method = "/^lambda\\$scheduleSave\\$\\d+$/",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;ioPool()Lnet/minecraft/TracingExecutor;"),
            require = 2)
    private TracingExecutor ephemeris$writeOnTheLevelsOwn() {
        return RuntimeLevelSaving.writerFor((SavedDataStorage) (Object) this);
    }
}
