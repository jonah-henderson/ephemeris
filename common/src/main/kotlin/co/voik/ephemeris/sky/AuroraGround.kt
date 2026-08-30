package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable

/**
 * Where a curtain may be seen from — the rule an [Aurora] carries about the ground under the viewer.
 *
 * **A rule rather than a list of biomes**, which is the whole of what makes it worth having: a list has to
 * be maintained, says nothing about a biome a pack added, and cannot answer for the mountain top in a
 * temperate world. A rule is asked of the position and answers for all three.
 */
enum class AuroraGround(private val key: String) : StringRepresentable {
    /** Over any ground at all. */
    ANYWHERE("anywhere"),

    /**
     * Only where the ground under the viewer is cold enough that what fell on it would be snow.
     *
     * **Vanilla's own snow-against-rain line**, `Biome.coldEnoughToSnow`, and that is the point rather than
     * a convenience: crossing from a snowy biome into a temperate one is a boundary every player has
     * already learned by watching the weather change as they walk over it. An aurora that keeps the same
     * line needs no explaining.
     *
     * It reads a *position* and not a biome, so vanilla's height falloff comes with it and a cold enough
     * peak in a temperate world will show one. That is vanilla being consistent rather than a special case,
     * and it is the same reason snow lies on a mountain the plain below it never sees.
     */
    WHERE_IT_SNOWS("where_it_snows"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<AuroraGround> = StringRepresentable.fromEnum(AuroraGround::values)
    }
}
