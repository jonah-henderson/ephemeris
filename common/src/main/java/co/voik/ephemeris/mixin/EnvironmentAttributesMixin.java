package co.voik.ephemeris.mixin;

import co.voik.ephemeris.sky.LevelClock;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets a level's timelines run on the hour its own sky is at.
 *
 * <p><b>Every visual cue for the time of day is one timeline.</b> {@code Timelines.OVERWORLD_DAY} keyframes
 * the sky colour, the fog, the cloud tint, the light's colour and factor, the sunrise band, the star
 * brightness and the sky light level — all against one clock. A level with two suns therefore had a working
 * day and <i>looked</i> like the overworld's night on the overworld's schedule, because none of that list
 * knows a second sun exists.
 *
 * <p>{@code addDefaultLayers} hands {@code level.clockManager()} to every sampler it bakes, so this one
 * redirect moves all of them together, in vanilla's own curves, agreeing with each other for free. Restating
 * the tracks instead would mean picking a dozen colours by hand and keeping them in step with a version of
 * Minecraft that owns them.
 *
 * <p>No loader event comes near this, and no data can express it: a runtime level cannot be given a timeline
 * of its own, timelines being datapack content frozen at startup.
 *
 * <p>Every track but three, that is: {@link TimelineLayersMixin} holds the sun, moon and star angles on the
 * real clock, the moved hour being allowed to leap and a position not.
 */
@Mixin(net.minecraft.world.attribute.EnvironmentAttributeSystem.class)
public class EnvironmentAttributesMixin {

    @Redirect(
            method = "addDefaultLayers",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clockManager()Lnet/minecraft/world/clock/ClockManager;"))
    private static ClockManager ephemeris$readTheLevelsOwnHour(Level level) {
        return LevelClock.INSTANCE.forTimelines(level, level.clockManager());
    }
}
