package co.voik.ephemeris

import co.voik.ephemeris.client.AuroraPainter
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.CloudPainter
import co.voik.ephemeris.client.HorizonPainter
import co.voik.ephemeris.client.CloudMoment
import co.voik.ephemeris.client.LevelCloudRenderer
import co.voik.ephemeris.client.LevelDaytime
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.LookArrivals
import co.voik.ephemeris.client.RainbowPainter
import co.voik.ephemeris.client.SkyPainter
import co.voik.ephemeris.sky.LevelLookPayload
import co.voik.ephemeris.sky.LevelLooks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties

/**
 * Ephemeris on a Fabric client: learning what each level looks like, and forgetting it again.
 *
 * The renderers read [LevelLooks] every frame rather than registering per dimension, which is what lets a
 * level change its appearance while a client is connected, and is why nothing here is keyed by dimension.
 */
fun initClient() {
    registerTheBuiltInPainters()
    RangeSelectItemModelProperties.ID_MAPPER.put(LevelDaytime.ID, LevelDaytime.MAP_CODEC)

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
            moment.level,
            moment.camera,
            moment.sunAngle,
            moment.moonAngle,
            moment.starAngle,
            moment.moonPhase,
            moment.rainBrightness,
            moment.starBrightness,
        )
    }
    // **Both halves**, which is what 26.3 asks of anything drawn in the transparency pass: what a deck
    // needs uploaded is uploaded where no pass is open, and the draw only draws.
    LevelRendering.clouds(object : LevelCloudRenderer {
        override fun ready(moment: CloudMoment) =
            CloudPainter.ready(Blaze3dSkyCanvas, moment.level, moment.cameraPosition, moment.time)

        override fun draw(moment: CloudMoment): Boolean =
            CloudPainter.draw(Blaze3dSkyCanvas, moment.level, moment.cameraPosition, moment.time)
    })
    LevelRendering.horizon { moment -> HorizonPainter.draw(Blaze3dSkyCanvas, moment.level) }
    LevelRendering.skyOverlay { moment ->
        AuroraPainter.draw(
            Blaze3dSkyCanvas,
            moment.level,
            moment.camera,
            moment.rainBrightness,
            moment.starBrightness,
        )
    }
    LevelRendering.skyOverlay { moment ->
        RainbowPainter.draw(
            Blaze3dSkyCanvas,
            moment.level,
            moment.sunAngle,
            moment.moonAngle,
            moment.rainBrightness,
        )
    }
    LevelRendering.environment(HorizonPainter::silenceVanillasGlow)
    // A level told something new has to drop what it baked from the old answer, and build its air again.
    LevelLooks.whenTold = LookArrivals::told
}
