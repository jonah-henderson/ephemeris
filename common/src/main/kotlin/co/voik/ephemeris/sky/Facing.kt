package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * How a body's sprite is turned, as distinct from where the body is.
 *
 * **Vanilla never rolls its sun or its moon**, and the reason is worth knowing: it sweeps about the quad's
 * own local X, so that axis is untouched by the sweep and stays put in world space. A crescent's horns point
 * the same way at moonrise as at moonset, and never turn over.
 */
enum class Facing(private val key: String) {
    /**
     * Level with the horizon, and steady — **the default, and what vanilla looks like**.
     *
     * Vanilla's own sprite never turns, because it sweeps about its quad's sideways axis and that axis is
     * horizontal: level, and left alone. A path tipped out of vanilla's plane has a sideways axis that is
     * *not* horizontal, and keeping it fixed makes the sprite appear to turn as the body crosses the sky —
     * which is what a walk saw. So a path whose frame is already level keeps it, exactly reproducing
     * vanilla, and any other path is levelled.
     *
     * **Decided per path, not per instant** — see [CelestialPath.framesAreLevel]. Levelling has one place it
     * cannot answer, a body straight overhead or straight underfoot, where no horizontal direction is square
     * to it; a circle reaching the zenith is vanilla's own case and keeps its frame, and the nadir is below
     * the world.
     */
    LIKE_VANILLA("like_vanilla"),

    /**
     * Rolled by the path itself, so the sprite turns as the body travels.
     *
     * A thing vanilla cannot do at all, and so ours to offer rather than to match. On a tilted circle it
     * holds the sprite fixed in *world* space, which reads as a slow turn against the horizon.
     */
    ALONG_PATH("along_path"),
    ;

    /**
     * [alongPath] turned the way this asks.
     *
     * [pathIsLevel] is asked of the whole path rather than of this instant — see
     * [CelestialPath.framesAreLevel]. A path that tilts and untilts as it travels would otherwise toggle
     * between two different answers and the sprite would jump.
     */
    fun turn(alongPath: Quaternionf, pathIsLevel: Boolean): Quaternionf = when (this) {
        ALONG_PATH -> alongPath
        // A path already level is vanilla's own case, and there its frame *is* the answer — reproduced
        // exactly rather than rebuilt into something equal to it.
        LIKE_VANILLA -> if (pathIsLevel) alongPath else levelled(alongPath)
    }

    companion object {
        val CODEC: Codec<Facing> = Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: LIKE_VANILLA },
            { it.key },
        )

        /**
         * [alongPath] turned about the line of sight until the sprite is level with the horizon.
         *
         * The sideways axis becomes the one horizontal direction square to the body. Overhead there is no
         * such direction — the same singularity that makes a bearing undefined there — and the path's own
         * frame is kept instead.
         *
         * **Continuous everywhere it is defined, and that is the most that can be said.** It does not agree
         * with the path's frame about which way round is which, which is why a path that is already level
         * keeps its own rather than being handed an equal-but-mirrored one.
         */
        fun levelled(alongPath: Quaternionf): Quaternionf {
            val up = alongPath.transform(Vector3f(0.0f, 1.0f, 0.0f))
            val right = Vector3f(up).cross(WORLD_UP)
            if (right.lengthSquared() < OVERHEAD) return alongPath
            right.normalize()
            val forward = Vector3f(right).cross(up)
            return Quaternionf().setFromNormalized(
                Matrix3f().set(right.x, right.y, right.z, up.x, up.y, up.z, forward.x, forward.y, forward.z),
            )
        }

        private val WORLD_UP = Vector3f(0.0f, 1.0f, 0.0f)

        /**
         * How square to the vertical a body must be before its frame is left alone. A body this near the
         * zenith is within a degree of it.
         */
        private const val OVERHEAD = 0.0003f
    }
}
