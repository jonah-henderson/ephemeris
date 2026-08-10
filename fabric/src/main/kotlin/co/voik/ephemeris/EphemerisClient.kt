package co.voik.ephemeris

import co.voik.ephemeris.sky.LevelLookPayload
import co.voik.ephemeris.sky.LevelLooks
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Ephemeris on a Fabric client: learning what each level looks like, and forgetting it again.
 *
 * The renderers read [LevelLooks] every frame rather than registering per dimension, which is what lets a
 * level change its appearance while a client is connected — and is why nothing is registered here but the
 * receiver.
 */
fun initClient() {
    ClientPlayNetworking.registerGlobalReceiver(LevelLookPayload.TYPE) { payload, _ ->
        LevelLooks.remember(payload)
    }

    // Forgotten on disconnect: these keys mean nothing on the next server and an id can be reused.
    ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> LevelLooks.forgetAll() }
}
