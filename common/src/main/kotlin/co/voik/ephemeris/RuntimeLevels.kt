package co.voik.ephemeris

import com.google.common.collect.ImmutableList
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.storage.DerivedLevelData

/**
 * Levels made after the server has started, on either loader.
 *
 * **The whole of the technique is that vanilla already does this**, just earlier. `MinecraftServer` builds
 * every non-overworld level from `(executor, storageSource, DerivedLevelData(worldData, overworldData),
 * dimension, stem)` and puts it in its `levels` map; this does the same thing later. What that costs is four
 * access-widener lines and no Mixin at all.
 *
 * **The `LevelStem` registry is deliberately not touched, and that is the finding this rests on.**
 * `ServerLevel`'s constructor takes a stem *directly* — it reads `type()` and `generator()` off it and never
 * looks one up — and every vanilla reader of `Registries.LEVEL_STEM` is a startup or world-creation path:
 * the boot loop, the datapack loader, the world-select screen, the optimise-world tool. Nothing reads it
 * during play. So a runtime level needs no registry surgery, and is invisible to exactly the machinery that
 * has no business creating it.
 *
 * The visible consequence is that vanilla does not persist these in `level.dat` and will not rebuild them at
 * boot. **That is the caller's job and it is the right place for it**: whatever decided a level should exist
 * knows how to describe it, and re-opening one is [open] again with the same id.
 */
object RuntimeLevels {

    /**
     * Get the level [id], building it if the server has not got one.
     *
     * **Idempotent, and the only entry point on purpose.** A caller re-opening a level after a restart calls
     * exactly what a caller creating one calls, so there is no "create" that fails on the second boot and no
     * "load" that fails on the first. Chunks already on disk are picked up by being at the same id.
     *
     * Must be called **on the server thread**: it mutates the level map the tick loop walks.
     */
    fun open(server: MinecraftServer, id: Identifier, config: RuntimeLevelConfig): ServerLevel {
        val dimension = ResourceKey.create(Registries.DIMENSION, id)
        server.levels[dimension]?.let { return it }

        val stem = LevelStem(config.dimensionType, config.generator)
        val level = ServerLevel(
            server,
            server.executor,
            server.storageSource,
            // Derived, as vanilla's own secondary levels are: the world's shared state (the difficulty, the
            // game rules, whether it has been initialised) stays one thing, and only what is genuinely
            // per-level diverges.
            DerivedLevelData(server.worldData, server.worldData.overworldData()),
            dimension,
            stem,
            false,
            BiomeManager.obfuscateSeed(config.seed),
            ImmutableList.of(),
            config.tickTime,
        )

        server.levels[dimension] = level
        // Vanilla does both of these to every level it builds, and a level without them has no border and
        // never tells a joining player where the border is.
        level.worldBorder.setAbsoluteMaxSize(server.absoluteMaxWorldSize)
        server.playerList.addWorldborderListener(level)

        // The loader has to be told the map changed — see [RuntimeLevelPlatform] for why this cannot be done
        // from here.
        RuntimeLevelPlatform.of().levelOpened(server, level)
        RuntimeLevelEvents.opened(level)
        return level
    }

    /**
     * Close the level [id] and discard what it saved, returning whether anything went.
     *
     * **Order is the whole of the correctness here**, and each step is why the next one is safe:
     *
     * 1. **Tell listeners while it still works.** A listener asked to save or move what it owns needs a
     *    level it can read, so this happens before anything is taken apart.
     * 2. **Out of the map**, so no tick can reach a level that is about to be closed. Everything that walks
     *    the world — saving, ticking, `/execute in` — reads that map, so removal is what makes the rest of
     *    this unobservable rather than a race.
     * 3. **Then the loader**, which on NeoForge is where the cached level array is invalidated. Doing it
     *    before the removal would rebuild the array *with* the level still in it.
     * 4. **Then close**, which flushes and releases the region files — before, not after, deleting them.
     *    Region files are memory-mapped and an unlink under an open handle is how a delete succeeds and the
     *    directory is still there.
     *
     * Safe to call for a level that was never opened; the files are discarded either way.
     */
    fun delete(server: MinecraftServer, id: Identifier): Boolean {
        val dimension = ResourceKey.create(Registries.DIMENSION, id)
        // Nothing here may ever take the overworld apart, whatever it is asked.
        if (dimension in VANILLA_LEVELS) return false

        server.levels[dimension]?.let { level ->
            RuntimeLevelEvents.closing(level)
            server.levels.remove(dimension)
            RuntimeLevelPlatform.of().levelClosing(server, level)
            runCatching { level.close() }.onFailure {
                RuntimeLevelLog.warn("Could not close $id cleanly; its files are left alone", it)
                return false
            }
        }
        return discard(server, dimension)
    }

    /**
     * Remove a closed level's saved chunks.
     *
     * **Fenced on where the path is, not on what it is called.** This is the only place the library deletes
     * anything, so the check is that the directory really is a *dimension folder inside this world* — under
     * `<level>/dimensions`, and deeper than it. A malformed id, a `..`, or a storage layout that moved all
     * fail the same way: nothing is removed and it says so.
     */
    private fun discard(server: MinecraftServer, dimension: ResourceKey<Level>): Boolean {
        val world = server.storageSource.levelDirectory.path().toAbsolutePath().normalize()
        val dimensions = world.resolve(DIMENSIONS_FOLDER)
        val folder = server.storageSource.getDimensionPath(dimension).toAbsolutePath().normalize()
        val isInsideThisWorld = folder.startsWith(dimensions) && folder != dimensions
        if (!isInsideThisWorld) {
            RuntimeLevelLog.warn("Refusing to discard $folder — it is not a dimension folder of this world")
            return false
        }
        if (!folder.toFile().isDirectory) return true
        val gone = folder.toFile().deleteRecursively()
        if (!gone) RuntimeLevelLog.warn("Some of $folder could not be removed and is left behind")
        return gone
    }

    /** Whether the server already holds this level, without building one to find out. */
    fun isOpen(server: MinecraftServer, id: Identifier): Boolean =
        ResourceKey.create(Registries.DIMENSION, id) in server.levels

    /** Vanilla's own name for the folder every non-vanilla level saves under. */
    private const val DIMENSIONS_FOLDER = "dimensions"

    /** Every level this library opened, in the order they were opened. */
    fun opened(server: MinecraftServer): List<ServerLevel> =
        server.levels.values.filter { it.dimension() !in VANILLA_LEVELS }

    private val VANILLA_LEVELS: Set<ResourceKey<Level>> = setOf(Level.OVERWORLD, Level.NETHER, Level.END)
}
