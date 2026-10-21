package co.voik.ephemeris.client

import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.Lightmap
import net.minecraft.client.renderer.state.LightmapRenderState
import net.minecraft.world.attribute.EnvironmentAttributes

/**
 * The lightmap a level drawn off-screen is lit by, which [OffscreenLevelRender.drawing] lights as its scope opens.
 *
 * **Vanilla keeps one lightmap, lit from the player's camera.** Its sky factor and its sky, ambient and block-light
 * colours are environment attributes probed where the player stands, so a level drawn anywhere else was lit by the
 * player's time of day and the player's surroundings: a daylit level seen from a cave came out as dark as the cave.
 * This one reads those from the camera the off-screen frame is seen from, and the level being drawn's end flash.
 *
 * **What belongs to the viewer stays the player's**: the brightness setting, darkness, night vision, the boss bar's
 * darkening and the flicker of block light are copied from the frame the player's own lightmap was worked out for.
 */
object OffscreenLightmap {

    private var lightmap: Lightmap? = null

    /** Lights the lightmap for [level] seen from [camera]. On the render thread, where no render pass is open. */
    fun light(level: ClientLevel, camera: Camera) {
        val ours = lightmap ?: Lightmap().also { lightmap = it }
        ours.render(stateFor(level, camera))
    }

    /** What a renderer should sample while a level is drawn off-screen, or null where the player's lightmap stands. */
    fun view(): GpuTextureView? = if (OffscreenLevelRender.isDrawingElsewhere()) lightmap?.textureView else null

    private fun stateFor(level: ClientLevel, camera: Camera): LightmapRenderState {
        val minecraft = Minecraft.getInstance()
        val players = minecraft.gameRenderer.gameRenderState().lightmapRenderState
        val probe = camera.attributeProbe()
        val flash = level.endFlashState()?.takeUnless { minecraft.options.hideLightningFlash().get() }
        return LightmapRenderState().apply {
            needsUpdate = true
            blockFactor = players.blockFactor
            brightness = players.brightness
            darknessEffectScale = players.darknessEffectScale
            nightVisionEffectIntensity = players.nightVisionEffectIntensity
            bossOverlayWorldDarkening = players.bossOverlayWorldDarkening
            blockLightTint = probe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, WHOLE_TICK)
            skyFactor = probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, WHOLE_TICK) + (flash?.getIntensity(WHOLE_TICK) ?: 0f)
            skyLightColor = probe.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, WHOLE_TICK)
            ambientColor = probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, WHOLE_TICK)
            nightVisionColor = probe.getValue(EnvironmentAttributes.NIGHT_VISION_COLOR, WHOLE_TICK)
        }
    }

    /** What vanilla's own lightmap is worked out at. */
    private const val WHOLE_TICK = 1f
}
