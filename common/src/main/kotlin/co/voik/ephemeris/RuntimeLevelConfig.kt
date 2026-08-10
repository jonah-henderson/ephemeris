package co.voik.ephemeris

import net.minecraft.core.Holder
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.dimension.DimensionType

/**
 * What a level should be, as data — everything [RuntimeLevels] needs to build one.
 *
 * Deliberately the *whole* description and nothing more: a caller that can produce one of these can produce
 * a world, and a caller that cannot is missing something real rather than something ceremonial.
 */
data class RuntimeLevelConfig(
    /**
     * The dimension type, which must already be **registered and datapack-loaded**.
     *
     * This is the one thing a runtime level cannot invent. Dimension types are a synced registry: the client
     * is told the whole set when it joins, and a type that arrives afterwards is a type the client cannot
     * resolve when it is asked to change dimension. So types are content — files in a datapack — and only
     * *levels* are made at runtime.
     */
    val dimensionType: Holder<DimensionType>,
    /** What builds the terrain. Anything vanilla would accept, including a generator of the caller's own. */
    val generator: ChunkGenerator,
    /** The seed, which the level keeps and its generator is free to ignore. */
    val seed: Long,
    /**
     * Whether the level advances its own clock and weather.
     *
     * False is the ordinary answer for a level that should feel like part of the same world — vanilla's own
     * Nether and End are built this way, taking the overworld's time. True gives the level a day of its own.
     */
    val tickTime: Boolean = false,
)
