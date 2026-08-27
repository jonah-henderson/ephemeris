package co.voik.ephemeris

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * **The seed a runtime level generates from**, which vanilla has nowhere to put.
 *
 * `ServerLevel.getSeed()` answers the *world's* seed, and everything that decides terrain reads it: the
 * chunk cache builds its `RandomState` from it while the level's own constructor is still running, and
 * structure placement asks it for ever afterwards. So two levels sharing a world share their terrain unless
 * something intervenes — a runtime level built on vanilla's own generator came out identical however it was
 * seeded, which is a silent and total loss of the one number a caller passed in
 * (Jonah, 2026-08-27: two Ages at seeds 111 and 999 differed by two blocks in nine chunks).
 *
 * **NeoForge has a per-level seed and vanilla does not.** `LevelStem` carries a `neoforge:seed_override`
 * that its own `getSeed` consults; on Fabric there is no such field and no such route. Reaching for it
 * would compile against the merged sources and fail on the loader that has never heard of it, which is the
 * multiloader trap in its purest form. So this is ours on both sides, and is read by one injector.
 *
 * **Remembered before the level is built**, because the chunk cache asks during construction. Keyed by
 * dimension, since that is the one thing about a level that is settled before its body runs.
 */
object RuntimeLevelSeeds {

    private val seeds = ConcurrentHashMap<ResourceKey<Level>, Long>()

    /** What [dimension] generates from — call before building it, and it holds for the level's life. */
    fun remember(dimension: ResourceKey<Level>, seed: Long) {
        seeds[dimension] = seed
    }

    /** [dimension]'s own seed, or null where it has none and the world's is the right answer. */
    fun of(dimension: ResourceKey<Level>): Long? = seeds[dimension]

    /** Dropped with the level, so a re-opened one is seeded by whatever asks for it next. */
    fun forget(dimension: ResourceKey<Level>) {
        seeds.remove(dimension)
    }
}
