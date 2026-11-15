package co.voik.ephemeris.mixin.client;

import co.voik.ephemeris.client.SwirlingRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Whether a creature is drawn swirling, carried on its render state ({@link LivingEntityRendererMixin}). */
@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements SwirlingRenderState {

    @Unique
    private boolean ephemeris$swirling;

    @Override
    public boolean getEphemerisSwirling() {
        return ephemeris$swirling;
    }

    @Override
    public void setEphemerisSwirling(boolean swirling) {
        ephemeris$swirling = swirling;
    }
}
