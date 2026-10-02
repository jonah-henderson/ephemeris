package co.voik.ephemeris

import co.voik.ephemeris.client.AuroraPainter
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.CloudPainter
import co.voik.ephemeris.client.HorizonPainter
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.LookArrivals
import co.voik.ephemeris.client.RainbowPainter
import co.voik.ephemeris.client.SkyPainter
import co.voik.ephemeris.sky.LevelLooks
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * Ephemeris on a NeoForge client — a second `@Mod` with the same id and `dist = [Dist.CLIENT]`, so none of
 * this loads on a dedicated server.
 *
 * The Fabric half's note applies: the painters are ordinary renderers on the ordinary seam, registered
 * first so a consumer's own is asked first, and each declines any level nothing has described.
 */
@Mod(value = "ephemeris", dist = [Dist.CLIENT])
class EphemerisClient(eventBus: IEventBus) {

    init {
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
        LevelRendering.clouds { moment -> CloudPainter.draw(Blaze3dSkyCanvas, moment.level, moment.cameraPosition, moment.time) }
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

        NeoForge.EVENT_BUS.addListener(::onLoggingOut)
    }

    /** Forgotten on disconnect: these keys mean nothing on the next server and an id can be reused. */
    private fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        LevelLooks.forgetAll()
    }
}
