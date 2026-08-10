package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * How a body's sprite is turned, as distinct from where the body is.
 *
 * **Vanilla never rolls its sun or its moon**, and that is worth knowing because it is not obvious from the
 * code that draws them. Vanilla sweeps about the quad's own local X, so that axis is untouched by the sweep
 * and stays put in world space — the sprite tumbles along its arc without ever turning, and a crescent's
 * horns point the same way at moonrise as at moonset.
 *
 * Which means facing is not something a path should decide. [LIKE_VANILLA] holds a sprite upright whatever
 * shape it is travelling, so an epicycling moon still looks like a moon; [ALONG_PATH] lets it roll with its
 * own motion, which is a thing vanilla cannot do at all and so is ours to offer rather than to match.
 */
enum class Facing(private val key: String) {
    /** Upright, as vanilla's own bodies are, whatever the trajectory. */
    LIKE_VANILLA("like_vanilla"),

    /** Rolled by the path itself, so the sprite turns as the body travels. */
    ALONG_PATH("along_path"),
    ;

    companion object {
        val CODEC: Codec<Facing> = Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: LIKE_VANILLA },
            { it.key },
        )

        /**
         * [alongPath] with its roll taken out — the body left exactly where it was, the sprite stood upright.
         *
         * The frame is built from the body's own direction: **right** is the one horizontal direction square
         * to it, and **up** follows. For vanilla's own path that reproduces vanilla's frame exactly, because
         * vanilla's untouched sweep axis *is* that horizontal direction.
         *
         * **Overhead there is no such direction**, and no construction can invent one — a body at the zenith
         * has no horizontal square to it, the same singularity that makes its bearing undefined. There the
         * path's own frame is kept, which is continuous with what surrounds it and is never seen anyway: a
         * body that near the zenith is a disc face-on to the viewer.
         */
        fun upright(alongPath: Quaternionf): Quaternionf {
            val up = alongPath.transform(Vector3f(0.0f, 1.0f, 0.0f))
            val right = Vector3f(up).cross(WORLD_UP)
            if (right.lengthSquared() < OVERHEAD) return alongPath
            right.normalize()
            // **The sign comes from the path, not from the cross product.** Level is not enough on its own:
            // `up × worldUp` reverses as a body crosses the zenith, so a sprite kept level that way is also
            // mirrored at noon — which vanilla never does, its own axis being fixed. Agreeing with the frame
            // the path already had keeps the choice continuous, and on vanilla's path reproduces it exactly.
            if (right.dot(alongPath.transform(Vector3f(1.0f, 0.0f, 0.0f))) < 0.0f) right.negate()
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
