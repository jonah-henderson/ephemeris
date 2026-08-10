package co.voik.ephemeris

import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.CloudPainter
import co.voik.ephemeris.client.HorizonPainter
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyPainter
import co.voik.ephemeris.sky.LevelLookPayload
import co.voik.ephemeris.sky.LevelLooks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Ephemeris on a Fabric client: learning what each level looks like, and forgetting it again.
 *
 * The renderers read [LevelLooks] every frame rather than registering per dimension, which is what lets a
 * level change its appearance while a client is connected, and is why nothing here is keyed by dimension.
 */
fun initClient() {
    registerTheBuiltInPainters()

    ClientPlayNetworking.registerGlobalReceiver(LevelLookPayload.TYPE) { payload, _ ->
        LevelLooks.remember(payload)
    }

    // Forgotten on disconnect: these keys mean nothing on the next server and an id can be reused.
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> LevelLooks.forgetAll() }
}

/**
 * The batteries, registered for you.
 *
 * **They are ordinary renderers on the ordinary seam**, registered first so a consumer's own — added later —
 * is asked first and can take a level over completely. Each declines any level nothing has described, so a
 * world with no Ephemeris levels in it draws exactly as vanilla does and pays nothing.
 */
private fun registerTheBuiltInPainters() {
    LevelRendering.sky { moment ->
        SkyPainter.draw(
            Blaze3dSkyCanvas,
            moment.sunAngle,
            moment.moonAngle,
            moment.starAngle,
            moment.moonPhase,
            moment.rainBrightness,
            moment.starBrightness,
        )
    }
    LevelRendering.clouds { moment -> CloudPainter.draw(Blaze3dSkyCanvas, moment.cameraPosition, moment.time) }
    LevelRendering.horizon { HorizonPainter.draw(Blaze3dSkyCanvas) }
}
