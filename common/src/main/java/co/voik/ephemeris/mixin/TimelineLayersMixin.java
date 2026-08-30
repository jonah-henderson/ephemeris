package co.voik.ephemeris.mixin;

import co.voik.ephemeris.sky.LevelClock;
import net.minecraft.core.Holder;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.timeline.Timeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a level's moved hour off the three tracks that say where its sky's furniture is.
 *
 * <p><b>The companion to {@link EnvironmentAttributesMixin}, and it exists because one clock serves every
 * track.</b> {@code addDefaultLayers} reads {@code level.clockManager()} once and hands it to whole
 * timelines, so the hour a level is <i>lit as</i> was also the hour vanilla placed its sun, moon and stars
 * at — and that hour is allowed to leap. It leaps at equal sun heights, which costs the colours nothing and
 * costs a position everything: a moon short of the zenith reappeared the same distance past it.
 *
 * <p>This is the innermost seam where the attribute is still in hand. The loop is vanilla's own, one call
 * wider, so which clock each track reads can be asked per track; {@code LevelClock.forTrack} makes that
 * choice and nothing is decided here.
 *
 * <p>A level whose hour has not been moved is left entirely alone, so vanilla lays its own layers rather
 * than running through a copy of the same loop.
 */
@Mixin(EnvironmentAttributeSystem.Builder.class)
public abstract class TimelineLayersMixin {

    @Inject(method = "addTimelineLayer", at = @At("HEAD"), cancellable = true)
    private void ephemeris$placeTheSkyOnTheRealHour(
            Holder<Timeline> timeline,
            ClockManager clockManager,
            CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> layers) {
        if (!LevelClock.INSTANCE.movesTheHour(clockManager)) {
            return;
        }
        EnvironmentAttributeSystem.Builder builder = (EnvironmentAttributeSystem.Builder) (Object) this;
        for (EnvironmentAttribute<?> attribute : timeline.value().attributes()) {
            ephemeris$layTrack(builder, timeline.value(), attribute, clockManager);
        }
        layers.setReturnValue(builder);
    }

    private static <Value> void ephemeris$layTrack(
            EnvironmentAttributeSystem.Builder builder,
            Timeline timeline,
            EnvironmentAttribute<Value> attribute,
            ClockManager clockManager) {
        ClockManager clock = LevelClock.INSTANCE.forTrack(attribute, clockManager);
        builder.addTimeBasedLayer(attribute, timeline.createTrackSampler(attribute, clock));
    }
}
