package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import org.joml.Quaternionf

/**
 * How a body's sprite is turned, as distinct from where the body is.
 *
 * **Vanilla never rolls its sun or its moon within their own frame**, and the reason is worth knowing: it
 * sweeps about the quad's own local X, so that axis is untouched by the sweep and the sprite is carried
 * around rigidly. Both kinds here keep that — one turns the rigid frame once so it comes up level, and the
 * other leaves it where the path put it.
 */
enum class Facing(private val key: String) {
    /**
     * **Comes up level and stays as it came up — the default, and what vanilla looks like.**
     *
     * The square's top and bottom edges lie along the horizon as the body rises, and that orientation is
     * then locked to the path for the whole way round. So the sprite is upside down at setting relative to
     * rising, which is not a fault but the thing being reproduced: vanilla's crescent does exactly this.
     *
     * **One turn per path, never a levelling per instant** — see [CelestialPath.levellingTurn], which has
     * the argument for why the second cannot work at all.
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
     * [alongPath] turned the way this asks, [levellingTurn] being the path's own — see
     * [CelestialPath.levellingTurn].
     *
     * About the frame's own vertical, which is the line of sight to the body, so this rolls the sprite and
     * cannot move it. A path that already rises level answers zero and is left untouched.
     */
    fun turn(alongPath: Quaternionf, levellingTurn: Float): Quaternionf = when (this) {
        ALONG_PATH -> alongPath
        LIKE_VANILLA -> Quaternionf(alongPath).rotateY(levellingTurn)
    }

    companion object {
        val CODEC: Codec<Facing> = Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: LIKE_VANILLA },
            { it.key },
        )
    }
}
