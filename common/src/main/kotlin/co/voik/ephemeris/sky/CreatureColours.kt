package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier

/**
 * The colour a level's creatures are drawn in, every texture they wear recoloured toward it
 * ([co.voik.ephemeris.Recolouring]): [everyCreature] for all of them, and [byType] by entity type, which wins.
 */
data class CreatureColours(val everyCreature: Rgba? = null, val byType: Map<Identifier, Rgba> = emptyMap()) {

    /** The colour a creature of [type] is drawn in, or null for its own. */
    fun of(type: Identifier): Rgba? = byType[type] ?: everyCreature

    val isNone: Boolean get() = everyCreature == null && byType.isEmpty()

    companion object {
        val NONE = CreatureColours()

        val CODEC: Codec<CreatureColours> = RecordCodecBuilder.create { instance ->
            instance.group(
                Rgba.CODEC.optionalFieldOf("every_creature").forGetter { java.util.Optional.ofNullable(it.everyCreature) },
                Codec.unboundedMap(Identifier.CODEC, Rgba.CODEC).optionalFieldOf("by_type", emptyMap())
                    .forGetter(CreatureColours::byType),
            ).apply(instance) { every, byType -> CreatureColours(every.orElse(null), byType) }
        }
    }
}
