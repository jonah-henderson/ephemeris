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
     * The path's own frame — **what vanilla does, and the default**.
     *
     * For any circle this holds the sprite's sideways axis fixed in world space, exactly as vanilla holds
     * its own: steady, never flipping, whatever the path is doing. A path tipped out of vanilla's plane
     * carries its sprite tipped with it, which is a lean rather than a spin and reads as a body on a tilted
     * orbit should.
     */
    LIKE_VANILLA("like_vanilla"),

    /**
     * Held level to the horizon — horns flat whatever the path.
     *
     * **Offer it knowing what it costs.** Levelling means turning the sprite about the line of sight, and at
     * the very top and bottom of an arc there is no shortest way round: the body is momentarily at the
     * extreme of its climb, both turns are equally short, and the sprite flips end for end. On a *flat* path
     * — one circling at a constant height — that is true at every instant, and the sprite flickers every
     * frame. Right for a body that never goes high, wrong for anything that does.
     */
    LEVEL("level"),
    ;

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
         * with the path's frame about which way round is which, so [LEVEL] and [LIKE_VANILLA] are genuinely
         * different answers rather than one being a tidied version of the other.
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
