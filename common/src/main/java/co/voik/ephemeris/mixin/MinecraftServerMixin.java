package co.voik.ephemeris.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets a level open or close while the levels are ticking.
 *
 * <p>Vanilla's tick loop walks the level map itself, so a level opened from inside another's tick — an item
 * ticking in a mob's hand, a chunk future completing on the server thread — threw
 * {@code ConcurrentModificationException} out of the server. The loop walks a copy taken as it begins instead:
 * a level opened during it ticks from the next tick, and one closed during it is skipped by
 * {@code ServerLevelMixin}.
 *
 * <p>NeoForge's loop walks a level array of its own already, so there it finds nothing to redirect.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Redirect(
            method = "tickChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getAllLevels()Ljava/lang/Iterable;"),
            require = 0)
    private Iterable<ServerLevel> ephemeris$levelsAsTheTickBegan(MinecraftServer server) {
        return ImmutableList.copyOf(server.getAllLevels());
    }
}
