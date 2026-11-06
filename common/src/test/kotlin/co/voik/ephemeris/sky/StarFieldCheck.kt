package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

class StarFieldCheck : FunSpec({

    fun arrived(field: StarField): StarField =
        StarField.CODEC.parse(JsonOps.INSTANCE, StarField.CODEC.encodeStart(JsonOps.INSTANCE, field).getOrThrow())
            .getOrThrow()

    test("a field's tint survives the trip to a client, and an untinted one arrives untinted") {
        val red = StarField(780, 1L, tint = Rgba(1.0f, 0.3f, 0.2f))
        check(arrived(red) == red) { "a red field arrived as ${arrived(red)}" }
        check(arrived(StarField(780, 1L)).tint == null) { "an untinted field arrived tinted" }
    }

    test("a tinted field is not a sky vanilla can draw") {
        val tinted = SkySpec.VANILLA.copy(stars = SkySpec.VANILLA.stars.copy(tint = Rgba(1.0f, 0.3f, 0.2f)))
        check(SkySpec.VANILLA.isOrdinary) { "vanilla's own sky was not ordinary" }
        check(!tinted.isOrdinary) { "a tinted star field was left to vanilla, which cannot colour it" }
    }
})
