package co.voik.ephemeris.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.Look
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import java.util.concurrent.ConcurrentHashMap

/**
 * The grass, leaves and litter of a level, painted the colour that level was written with.
 *
 * **A level cannot repaint a biome, and must not want to.** Vanilla resolves these three off the biome, and
 * a runtime level borrows the registry's biomes — so writing a colour onto `minecraft:forest` would repaint
 * every forest in every world including the overworld's. Answering per *level* instead leaves the registry
 * untouched, which is the same reasoning that keeps an Age's dimension out of `LevelStem`.
 *
 * **The seam is one rung above the resolver.** [ColorResolver] is handed a biome and a position and knows
 * nothing about where it is, which is why nothing can be done at that level; `ClientLevel.calculateBlockTint`
 * has the level, and hands the resolver to its own blending loop. So this wraps the resolver on the way in
 * and vanilla's blend runs unchanged over it — which is what makes a boundary between a repainted biome and
 * an ordinary one blend correctly with nothing written for it.
 *
 * **Nothing here is per block.** `ClientLevel` keeps a `BlockTintCache` per resolver, so this is asked once
 * per cache miss and the answer is baked into the chunk mesh from there.
 */
object GroundTints {

    /**
     * [resolver] as this level wants it answered, or [resolver] itself where the level says nothing.
     *
     * Returning the original is the ordinary case and costs one map read: every level that is not one of
     * ours, and every one of ours that never mentioned the ground, goes straight through.
     */
    fun wrap(level: ClientLevel, resolver: ColorResolver): ColorResolver {
        val ground = Ground.of(resolver) ?: return resolver
        val look = LevelLooks.of(level.dimension()) ?: return resolver
        val painted = paintedIn(level, look)
        if (painted.saysNothingAbout(ground)) return resolver
        return ColorResolver { biome, x, z ->
            val said = ground.colourIn(painted.of(biome))
            if (said == null) resolver.getColor(biome, x, z) else ground.settleOn(biome, said, x, z)
        }
    }

    /** One of the three tints a level may repaint, and how vanilla arrives at each. */
    private enum class Ground {
        GRASS {
            override fun colourIn(look: Look): Rgba? = look.grass

            /**
             * **Under the biome's own modifier, but only where that modifier reads what it is given.**
             *
             * A dark forest's darkening is shape rather than colour — it averages whatever base it is
             * handed toward a fixed green — so keeping it is what lets a repainted dark forest still read
             * as one. A **swamp's is not a modifier at all**: `GrassColorModifier.SWAMP` ignores its base
             * outright and answers one of two hardcoded colours off a noise field, so layering under it
             * throws the Age's colour away and the swamp comes out its ordinary green (Jonah, 2026-08-30,
             * walked: "in purple grass, swamps still have dark green grass").
             *
             * So the question is asked of the modifier rather than of its name: hand it two very different
             * bases at this very position and see whether it answers differently. One that does is shaping
             * a colour and is kept; one that does not is *replacing* it, and there is nothing of the Age
             * left in what it returns. That also answers correctly for a modifier some other mod added.
             */
            override fun settleOn(biome: Biome, colour: Rgba, x: Double, z: Double): Int {
                val modifier = biome.specialEffects.grassColorModifier()
                val shapesWhatItIsGiven =
                    modifier.modifyColor(x, z, PITCH_BLACK) != modifier.modifyColor(x, z, PAPER_WHITE)
                return if (shapesWhatItIsGiven) modifier.modifyColor(x, z, colour.packed()) else colour.packed()
            }
        },
        FOLIAGE {
            override fun colourIn(look: Look): Rgba? = look.foliage
        },
        DRY_FOLIAGE {
            override fun colourIn(look: Look): Rgba? = look.dryFoliage
        },
        ;

        abstract fun colourIn(look: Look): Rgba?

        /** What vanilla does to the base colour once it has one. Only grass has anything. */
        open fun settleOn(biome: Biome, colour: Rgba, x: Double, z: Double): Int = colour.packed()

        companion object {
            /**
             * Which tint [resolver] is, **by identity**, or null for one that is not ours to answer.
             *
             * `BiomeColors`' four are singletons and `ClientLevel` keys its caches on those same instances,
             * so identity is the same question the cache is already asking. Water is deliberately absent:
             * it is a fluid tint with a research note of its own.
             */
            fun of(resolver: ColorResolver): Ground? = when (resolver) {
                BiomeColors.GRASS_COLOR_RESOLVER -> GRASS
                BiomeColors.FOLIAGE_COLOR_RESOLVER -> FOLIAGE
                BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER -> DRY_FOLIAGE
                else -> null
            }
        }
    }

    /**
     * A level's answer resolved against the biome registry once, rather than per sample.
     *
     * The corners of a look are keyed by biome **id**, and a resolver is handed a [Biome]. Going from one to
     * the other means a registry reverse lookup, which is a poor thing to do twenty-five times per cache
     * miss — so it is done once per look and kept.
     */
    private class Painted(val look: LevelLook, private val corners: Map<Biome, Look>) {
        fun of(biome: Biome): Look = corners[biome] ?: look.air

        fun saysNothingAbout(ground: Ground): Boolean =
            ground.colourIn(look.air) == null && corners.values.none { ground.colourIn(it) != null }
    }

    /** Kept per level and rebuilt when that level is told something new. */
    private val painted = ConcurrentHashMap<ResourceKey<Level>, Painted>()

    private fun paintedIn(level: ClientLevel, look: LevelLook): Painted {
        val standing = painted[level.dimension()]
        // Compared by identity: a look is replaced wholesale rather than edited, so a new object is a new
        // answer and an unchanged one is the same object every frame.
        if (standing != null && standing.look === look) return standing
        val biomes = level.registryAccess().lookupOrThrow(Registries.BIOME)
        val corners = look.corners.mapNotNull { (id, corner) ->
            biomes.get(ResourceKey.create(Registries.BIOME, id)).map { it.value() to corner }.orElse(null)
        }.toMap()
        return Painted(look, corners).also { painted[level.dimension()] = it }
    }

    /**
     * Forget what was baked for [dimension], because it has been told something new.
     *
     * **Both halves are needed and neither is enough.** The tint cache holds the colours already resolved,
     * and the chunk meshes hold them again in their vertices — a level whose grass changes while somebody
     * is standing in it keeps the old colour until both are dropped. On joining an Age neither exists yet,
     * so this does nothing and costs nothing, which is the ordinary case.
     */
    fun forget(dimension: ResourceKey<Level>) {
        painted.remove(dimension)
        val minecraft = net.minecraft.client.Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (level.dimension() != dimension) return
        level.clearTintCaches()
        minecraft.levelRenderer.allChanged()
    }

    /**
     * A leaf block's colour where the level repaints its leaves, and [otherwise] where it does not.
     *
     * **The one thing the resolver cannot reach.** Spruce and birch leaves are registered in `BlockColors`
     * with `BlockTintSources.constant(…)` — a fixed number that never asks the biome anything — so a level
     * repainting its foliage left those two stubbornly green while every other leaf followed (Jonah,
     * 2026-08-30, walked). Nothing is wrong with the resolver; those blocks simply never call one.
     *
     * So a leaf is answered by *what a leaf here looks like* rather than by what its own source says, which
     * routes back through [wrap] and picks up the biome blend on the way. A block with no tint source at
     * all never reaches here — cherry and pale oak are untinted in vanilla and stay that way.
     */
    fun leafTintOr(state: BlockState, level: BlockAndTintGetter, pos: BlockPos, otherwise: () -> Int): Int {
        if (!state.`is`(BlockTags.LEAVES)) return otherwise()
        // **The level being rendered, because the region handed in is a view and not a level.** Sections
        // compile off the render thread, so this can be a tick stale while a player changes dimension —
        // and a stale answer here is a leaf drawn the other level's colour until the section rebuilds,
        // which it is about to do anyway.
        val here = net.minecraft.client.Minecraft.getInstance().level ?: return otherwise()
        val look = LevelLooks.of(here.dimension()) ?: return otherwise()
        if (paintedIn(here, look).saysNothingAbout(Ground.FOLIAGE)) return otherwise()
        return BiomeColors.getAverageFoliageColor(level, pos)
    }

    /** For a caller that keeps its own store: whether an id names a biome this look repaints. */
    fun repaints(look: LevelLook, biome: Identifier): Boolean = look.corners.containsKey(biome)

    /** Two bases far enough apart that any modifier reading one at all answers them differently. */
    private const val PITCH_BLACK = 0xFF000000.toInt()
    private const val PAPER_WHITE = -1
}
