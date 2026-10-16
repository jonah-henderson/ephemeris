package co.voik.ephemeris.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.TimeCommand;
import net.minecraft.world.clock.ServerClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets {@code /time} set the clock of the level it is run in, where that level keeps one of its own
 * ({@code LevelClocks}).
 *
 * <p>Every reading and setting in the command is {@code source.getServer().clockManager()}, the server's; the
 * clock it names is already the source level's default one. So the source is noted as {@code getServer} is asked
 * and the manager is then the source level's — which is the server's for any level without a clock of its own,
 * so {@code /time} anywhere else is unchanged. No loader event reaches a vanilla command's internals, and
 * replacing the command would mean restating it.
 */
@Mixin(TimeCommand.class)
public abstract class TimeCommandMixin {

    /** The source the next {@code clockManager()} is asked for, on the server thread a command runs on. */
    @Unique
    private static final ThreadLocal<CommandSourceStack> EPHEMERIS$ASKING = new ThreadLocal<>();

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/commands/CommandSourceStack;getServer()Lnet/minecraft/server/MinecraftServer;"))
    private static MinecraftServer ephemeris$noteTheSource(CommandSourceStack source) {
        EPHEMERIS$ASKING.set(source);
        return source.getServer();
    }

    @Redirect(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;clockManager()Lnet/minecraft/world/clock/ServerClockManager;"))
    private static ServerClockManager ephemeris$theSourceLevelsClocks(MinecraftServer server) {
        CommandSourceStack source = EPHEMERIS$ASKING.get();
        EPHEMERIS$ASKING.remove();
        return source == null ? server.clockManager() : source.getLevel().clockManager();
    }
}
