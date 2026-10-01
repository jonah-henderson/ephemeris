package co.voik.ephemeris

import net.minecraft.core.Holder
import net.minecraft.world.level.CustomSpawner
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
    /**
     * The seed this level generates from, which its generator is free to ignore.
     *
     * **Kept by [RuntimeLevelSeeds] rather than by the level**, because `ServerLevel.getSeed()` answers the
     * *world's* seed and there is nowhere in vanilla to put another. Everything that decides terrain reads
     * that method, so without the substitution two runtime levels on the same generator come out identical
     * however they were seeded.
     */
    val seed: Long,
    /**
     * Whether the level advances its own clock and weather.
     *
     * False is the ordinary answer for a level that should feel like part of the same world — vanilla's own
     * Nether and End are built this way, taking the overworld's time. True gives the level a day of its own.
     */
    val tickTime: Boolean = false,
    /**
     * Spawners of the caller's own, ticked once per tick as vanilla ticks its phantoms and its patrols.
     *
     * **The seam for a creature the natural spawner will not place.** `NaturalSpawner` refuses a
     * `MobCategory.MISC` entity outright and validates every other against a box it must clear where it
     * stands, so a built creature and a very large one are both unreachable through it — and both are
     * ordinary things for a level to want. Vanilla's own answer to the same problem is this list: phantoms
     * arrive by it, and so do patrols, cats, sieges and wandering traders.
     *
     * Empty is vanilla's own default for a secondary level, and what every level had until a caller asked.
     */
    val customSpawners: List<CustomSpawner> = emptyList(),
    /**
     * Whether a visiting client should take this level's horizon to be its floor, as it does a superflat
     * world's: no dark disc drawn under the sky below sea level, and the void's darkness only at the very
     * bottom. For a level with nothing beneath its land, where sky belongs below you as much as above.
     *
     * **This is what `ServerLevel.isFlat()` means to the game** — its one reader is the spawn packet, and
     * the client reads nothing else off it — so answering it per level changes that and nothing more.
     * Vanilla answers it for the whole save at once.
     */
    val horizonAtTheFloor: Boolean = false,
)
