package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.LevelRain;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a level's rain fall at its own pace ({@code RainLook.pace}), and lean with its wind ({@code LevelRain.leanIn}).
 *
 * <p>A rain column's fall is its texture scrolled by game time times a speed drawn per column, in the private
 * {@code createRainColumnInstance}, which is handed no level. So the level's pace is read once as the columns
 * are extracted, and every column's speed scaled by it. Neither loader has a weather-render event.
 *
 * <p>A column is one quad from its top down to where it lands, built in {@code prepareInstances} for the rain's
 * columns and then the snow's. Its two top corners, the first two vertices, are moved upwind by the column's height
 * times the lean, so the streaks fall slanted and still land on their own column.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    private static final String PREPARE_INSTANCES = "prepareInstances";
    private static final String ADD_VERTEX =
            "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;";

    @Unique
    private float ephemeris$pace = 1.0f;

    @Unique
    private LevelRain.Lean ephemeris$lean = LevelRain.Lean.UPRIGHT;

    /** The snow's columns this frame, to tell the snow's pass through {@code prepareInstances} from the rain's. */
    @Unique
    private List<?> ephemeris$snowColumns = List.of();

    /** Which of [ephemeris$lean]'s two leans the columns being built take, along X and Z. */
    @Unique
    private float ephemeris$leanX;
    @Unique
    private float ephemeris$leanZ;

    /** The foot of the column being built, relative to the eye. */
    @Unique
    private float ephemeris$foot;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void ephemeris$readThePaceAndTheWind(
            ClientLevel level, float partialTicks, Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo info) {
        this.ephemeris$pace = LevelRain.of(level).getPace();
        this.ephemeris$lean = LevelRain.leanIn(level, partialTicks);
    }

    /** The column's speed: the second float local, after {@code partialTicks}. */
    @ModifyVariable(method = "createRainColumnInstance", at = @At("STORE"), ordinal = 1)
    private float ephemeris$slowTheColumn(float speed) {
        return speed * this.ephemeris$pace;
    }

    @Inject(method = "prepare", at = @At("HEAD"))
    private void ephemeris$noteTheSnow(Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo info) {
        this.ephemeris$snowColumns = renderState.snowColumns;
    }

    @Inject(method = PREPARE_INSTANCES, at = @At("HEAD"))
    private void ephemeris$chooseTheLean(
            VertexConsumer builder, List<?> columns, Vec3 cameraPos, float maxAlpha, int radius, float intensity,
            CallbackInfo info) {
        boolean isSnow = columns == this.ephemeris$snowColumns;
        this.ephemeris$leanX = isSnow ? this.ephemeris$lean.getSnowX() : this.ephemeris$lean.getRainX();
        this.ephemeris$leanZ = isSnow ? this.ephemeris$lean.getSnowZ() : this.ephemeris$lean.getRainZ();
    }

    @ModifyVariable(method = PREPARE_INSTANCES, at = @At("STORE"), name = "y0")
    private float ephemeris$noteTheFoot(float y0) {
        this.ephemeris$foot = y0;
        return y0;
    }

    @Redirect(method = PREPARE_INSTANCES, at = @At(value = "INVOKE", target = ADD_VERTEX, ordinal = 0))
    private VertexConsumer ephemeris$leanTheFirstTopCorner(VertexConsumer builder, float x, float y, float z) {
        return ephemeris$leaning(builder, x, y, z);
    }

    @Redirect(method = PREPARE_INSTANCES, at = @At(value = "INVOKE", target = ADD_VERTEX, ordinal = 1))
    private VertexConsumer ephemeris$leanTheSecondTopCorner(VertexConsumer builder, float x, float y, float z) {
        return ephemeris$leaning(builder, x, y, z);
    }

    @Unique
    private VertexConsumer ephemeris$leaning(VertexConsumer builder, float x, float y, float z) {
        float height = y - this.ephemeris$foot;
        return builder.addVertex(x + this.ephemeris$leanX * height, y, z + this.ephemeris$leanZ * height);
    }
}
