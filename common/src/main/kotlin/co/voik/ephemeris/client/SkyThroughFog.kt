package co.voik.ephemeris.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.material.FogType
import kotlin.math.max

/**
 * Whether a level's sky can be seen from where the eye is standing — or whether the fog has already closed
 * in front of it.
 *
 * **Vanilla draws half its sky unfogged, and has since the render rewrite.** The sky pass puts five things
 * on the screen and only the two discs carry a fog uniform at all:
 *
 * | drawn | pipeline | snippet |
 * | --- | --- | --- |
 * | sky disc, dark disc | `SKY` | `MATRICES_FOG_SNIPPET` |
 * | sunrise/sunset fan | `SUNRISE_SUNSET` | `MATRICES_PROJECTION_SNIPPET` |
 * | sun, moon | `CELESTIAL` | `MATRICES_PROJECTION_SNIPPET` |
 * | stars | `STARS` | `MATRICES_PROJECTION_SNIPPET` |
 *
 * `core/sky.fsh` ends in `apply_fog(…, FogSkyEnd, …, FogColor)`; `core/position_tex.fsh` and
 * `core/position_color.fsh` write `color * ColorModulator` and stop. Those three pipelines do not even
 * declare the `Fog` uniform block, so their shaders *cannot* fog, whatever the level asks for. Every
 * painter registered on [LevelRendering] was in the same position and for the same reason — a sky pipeline
 * was built without `Fog` because vanilla's are.
 *
 * **A cloud deck is the one that had to be answered differently, and is.** It hangs at a height of its own
 * rather than at a fixed distance, so how far away it is changes across the deck and from one frame to the
 * next — there is no distance to compare this rule against. `Blaze3dSkyCanvas.CLOUD_DECK_PIPELINE` carries
 * the `Fog` block and fades out against `FogCloudsEnd`, which is what vanilla's own clouds do. Everything
 * else in the sky stands at one distance, and this is what answers for all of it.
 *
 * So a level that says "you can see ten blocks" gets a sky disc faded honestly to the fog colour and then a
 * sun, a moon, a starfield and a hundred-degree sunset wash painted over it at full brightness. Underwater
 * in an abyss that reads as *the fog vanishing*, because the only thing left drawing the fog colour there
 * was the disc (Jonah, 2026-09-11, walked).
 *
 * **So the fog is given the one thing it cannot reach: whether to draw the sky at all.** That is the same
 * answer vanilla already gives for lava and powder snow, where `LevelRenderer.addSkyPass` declines to run
 * and the frame is left at the cleared fog colour — which is exactly the picture a fully fogged sky would
 * have made anyway.
 */
object SkyThroughFog {

    /**
     * How far out vanilla hangs everything in the sky pass that is not a disc, and it is one number for all
     * of them: `SUN_HEIGHT`, `MOON_HEIGHT`, the star radius, and the lit centre vertex of the sunrise fan
     * are each 100.
     *
     * The sky is drawn camera-centred with rotation only, so this is the distance from the eye as the fog
     * shader measures it — and since a body's own quad spreads *sideways* from that point, 100 is the
     * nearest any of it comes. Fog closing at or inside it leaves nothing of the sky to see.
     */
    const val WHERE_THE_BODIES_STAND = 100.0f

    /** Whether the medium the eye is in closes before the sky, so that nothing of it could be seen. */
    fun hidesTheSky(camera: Camera): Boolean = reachOfTheMedium(camera) <= WHERE_THE_BODIES_STAND

    /**
     * How far you can see through **what you are standing in**, which is the whole of the question.
     *
     * The same two numbers `FogRenderer` hands the sky shader as `FogSkyEnd`, read from the camera's own
     * probe rather than from the frame's fog buffer: a level drawn off-screen is seen through a camera of
     * its own standing somewhere else, and the buffer holds the *player's* air.
     *
     * **Vanilla's render-distance clamp is deliberately not applied**, though `AtmosphericFogEnvironment`
     * folds it into the same field. A short render distance is not a medium: it fades the sky to a fog
     * colour that is *itself* the sky's, so nothing reads as wrong and there is nothing to hide. Taking it
     * into account would cost the sun and stars to anyone playing under seven chunks.
     */
    private fun reachOfTheMedium(camera: Camera): Float {
        val partialTicks = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
        val probe = camera.attributeProbe()
        if (camera.fluidInCamera != FogType.WATER) {
            // 512 by default — the sky disc's own radius, which is to say no fog at all. A level only
            // reaches this rule by having said something shorter: a blizzard, a column of sand.
            return probe.getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, partialTicks)
        }
        // 96 by default, and **quartered until the eye adjusts** — `WaterFogEnvironment` scales the end by
        // `max(0.25, waterVision)` and the sky shader is handed the result. So ordinary water hides the sky
        // too, which it always should have: 96 stands inside 100.
        val end = probe.getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, partialTicks)
        val eye = camera.entity()
        return if (eye is LocalPlayer) end * max(0.25f, eye.waterVision) else end
    }
}
