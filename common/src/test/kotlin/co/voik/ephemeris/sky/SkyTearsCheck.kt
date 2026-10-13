package co.voik.ephemeris.sky

import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import org.joml.Vector3f
import kotlin.math.asin

/** That tears are laid out the same every time, high in the sky, and cross intact. */
class SkyTearsCheck : FunSpec({

    fun elevationOf(point: TearPoint): Double {
        val up = point.orientation.transform(Vector3f(0f, 1f, 0f))
        return Math.toDegrees(asin(up.y.toDouble().coerceIn(-1.0, 1.0)))
    }

    test("the same tears are laid out the same, and other seeds elsewhere") {
        val tears = SkyTears(seed = 7L)
        check(tears.points() == tears.points()) { "one set of tears was laid out two ways" }
        check(SkyTears(seed = 8L).points() != tears.points()) { "two seeds tore the sky alike" }
    }

    test("every point of a tear hangs high in the sky") {
        for (seed in 0L..<20L) {
            val lowest = SkyTears(seed = seed).points().minOf(::elevationOf)
            check(lowest > 28.0) { "seed $seed tore the sky down to %.1f° up".format(lowest) }
        }
    }

    test("as many tears as were asked for, and none for none") {
        check(SkyTears(count = 0).points().isEmpty()) { "no tears still drew points" }
        check(SkyTears(count = 2).points().size * 3 == SkyTears(count = 6).points().size) {
            "tears do not each draw the same number of points"
        }
    }

    test("tears cross on a sky") {
        val sky = SkySpec.VANILLA.copy(tears = SkyTears(count = 4, glow = 0.5f, seed = 3L))
        val written = SkySpec.CODEC.encodeStart(JsonOps.INSTANCE, sky).getOrThrow()
        check(SkySpec.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow().tears == sky.tears) { "the tears did not cross" }
    }
})
