package co.voik.ephemeris.client

import co.voik.ephemeris.mixin.client.LeafParticleAccessor
import co.voik.ephemeris.mixin.client.ParticleAccessor
import co.voik.ephemeris.sky.LevelWind
import co.voik.ephemeris.sky.Wind
import java.util.Collections
import java.util.WeakHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.BaseAshSmokeParticle
import net.minecraft.client.particle.CampfireSmokeParticle
import net.minecraft.client.particle.FallingDustParticle
import net.minecraft.client.particle.FallingLeavesParticle
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.SnowflakeParticle
import net.minecraft.client.particle.SuspendedParticle
import net.minecraft.client.particle.SuspendedTownParticle
import net.minecraft.client.particle.WhiteSmokeParticle
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.UntintedParticleLeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap

/**
 * What a level's wind carries: the light particles, moved downwind after each of their own ticks, snow thrown past
 * in falling snow ([WindDrivenSnow]), and in a strong enough wind, debris lifted off the ground around the player.
 *
 * **Carried after the particle has moved itself**, by the same `move` it uses, so a particle keeps everything its
 * own tick does — smoke still rises, leaves still flutter — and collides as it always did. Most of these override
 * `tick` without calling the base, which is why it is done where the engine ticks them rather than in `tick`.
 */
object WindBorne {

    /** The particles light enough to be carried: smoke, ash, leaves, spores, snow and dust. */
    private fun isLightEnough(particle: Particle): Boolean =
        particle is BaseAshSmokeParticle || particle is CampfireSmokeParticle || particle is WhiteSmokeParticle ||
            particle is FallingLeavesParticle || particle is SuspendedParticle || particle is SuspendedTownParticle ||
            particle is SnowflakeParticle || particle is FallingDustParticle

    /** [particle], in [level], carried one tick's worth downwind. */
    @JvmStatic
    fun carry(particle: Particle, level: ClientLevel) {
        if (!particle.isAlive || !isLightEnough(particle)) return
        val blowing = blowingIn(level)
        if (blowing.strength <= 0.0f) return
        if (isUnderWater(particle, level)) return
        val carried = blowing.strength * CARRIED_AT_FULL_STRENGTH
        particle.move(blowing.x * carried, 0.0, blowing.z * carried)
        if (particle in debris) (particle as ParticleAccessor).`ephemeris$setRising`(liftOf(particle, blowing.strength))
    }

    /**
     * How fast debris rises this tick: climbing gently off the ground for most of its flight, then taken up off it
     * towards the end, the harder the wind the higher. Set outright each tick, so its own gravity never builds an arc; a leaf keeps
     * its own flutter across.
     */
    private fun liftOf(particle: Particle, strength: Float): Double {
        val lifetime = particle.lifetime.coerceAtLeast(1)
        val through = ((particle as ParticleAccessor).`ephemeris$age`().toDouble() / lifetime).coerceIn(0.0, 1.0)
        val rising = ((through - SKIMS_FOR) / (1.0 - SKIMS_FOR)).coerceIn(0.0, 1.0)
        val eased = rising * rising * (3.0 - 2.0 * rising)
        return strength * (eased * RISES_AT_MOST + (1.0 - eased) * RISES_WHILE_SKIMMING)
    }

    /** Whether [particle] is in water, where no wind reaches it. */
    private fun isUnderWater(particle: Particle, level: ClientLevel): Boolean {
        val box = particle.boundingBox
        val at = BlockPos.containing((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2)
        return level.getFluidState(at).`is`(FluidTags.WATER)
    }

    /** The debris this lifted, which [carry] flies differently from the particles the wind only carries. */
    private val debris: MutableSet<Particle> = Collections.newSetFromMap(WeakHashMap())

    /**
     * Debris off the ground around the player, in a wind of at least [LIFTS_DEBRIS_FROM]: snow off snow, leaves off a
     * canopy as the leaves themselves let fall, and sand and leaf litter as dust of their own colour; nothing off any
     * other ground. Each starts at rest just over the ground and [carry] flies it: downwind along the ground, then up
     * off it.
     */
    @JvmStatic
    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        if (minecraft.isPaused) return
        val blowing = blowingIn(level)
        if (blowing.strength > 0.0f) WindDrivenSnow.throwPast(level, player, blowing)
        val overTheThreshold = (blowing.strength - LIFTS_DEBRIS_FROM) / (1.0f - LIFTS_DEBRIS_FROM)
        if (overTheThreshold <= 0.0f) return
        val random = level.random
        val cursor = BlockPos.MutableBlockPos()
        repeat((overTheThreshold * MOST_DEBRIS_A_TICK).toInt().coerceAtLeast(1)) {
            val x = player.blockX + random.nextInt(-AROUND, AROUND + 1)
            val z = player.blockZ + random.nextInt(-AROUND, AROUND + 1)
            val open = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            cursor.set(x, open, z)
            if (!level.canSeeSky(cursor)) return@repeat
            // Leaf litter and a thin snow layer lie in the open block itself, over whatever holds them up.
            val lying = level.getBlockState(cursor.set(x, open, z))
            val ground = if (isLoose(lying)) lying else level.getBlockState(cursor.set(x, open - 1, z))
            val lifted = liftedOff(ground, level, cursor) ?: return@repeat
            val particle = minecraft.particleEngine.createParticle(
                lifted,
                x + random.nextDouble(),
                open + random.nextDouble() * LIFTED_FROM_AS_HIGH_AS,
                z + random.nextDouble(),
                0.0,
                0.0,
                0.0,
            )
            if (particle == null) return@repeat
            particle.lifetime = SHORTEST_FLIGHT + random.nextInt(LONGEST_FLIGHT - SHORTEST_FLIGHT + 1)
            debris += particle
        }
    }

    /** Whether [ground] lies loose in the open block over the ground: leaf litter or a thin snow layer. */
    private fun isLoose(ground: BlockState): Boolean = ground.`is`(Blocks.LEAF_LITTER) || ground.`is`(BlockTags.SNOW)

    /** What the wind lifts off [ground] at [at], or null where it lifts nothing: sand, snow, leaves and leaf litter. */
    private fun liftedOff(ground: BlockState, level: ClientLevel, at: BlockPos): ParticleOptions? {
        val block = ground.block
        return when {
            ground.`is`(BlockTags.SNOW) -> ParticleTypes.SNOWFLAKE
            block is UntintedParticleLeavesBlock -> (block as LeafParticleAccessor).`ephemeris$leafParticle`()
            block is LeavesBlock -> ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, level.getClientLeafTintColor(at))
            ground.`is`(BlockTags.SAND) || ground.`is`(Blocks.LEAF_LITTER) -> BlockParticleOption(ParticleTypes.FALLING_DUST, ground)
            else -> null
        }
    }

    /** The wind in [level] this tick, worked out once per tick rather than once per particle. */
    private fun blowingIn(level: ClientLevel): Wind.Blowing {
        val time = level.gameTime
        if (level !== lastLevel || time != lastTime) {
            lastLevel = level
            lastTime = time
            lastBlowing = LevelWind.now(level)
        }
        return lastBlowing
    }

    private var lastLevel: ClientLevel? = null
    private var lastTime = Long.MIN_VALUE
    private var lastBlowing = Wind.Blowing.STILL

    /** How far a particle is carried each tick in the strongest wind: four blocks a second. */
    private const val CARRIED_AT_FULL_STRENGTH = 0.2

    /** The strength debris starts lifting at, and how much lifts each tick in the strongest wind. */
    private const val LIFTS_DEBRIS_FROM = 0.5f
    private const val MOST_DEBRIS_A_TICK = 8

    /** How far around the player debris lifts, and how high off the ground it starts. */
    private const val AROUND = 16
    private const val LIFTED_FROM_AS_HIGH_AS = 0.2

    /** How many ticks debris flies, at the least and the most: two to four and a half seconds. */
    private const val SHORTEST_FLIGHT = 40
    private const val LONGEST_FLIGHT = 90

    /** How much of its flight debris skims the ground before the wind takes it up. */
    private const val SKIMS_FOR = 0.55

    /**
     * How fast debris rises at the end of its flight in the strongest wind, and while it skims, a tick: enough while
     * skimming to stay off the ground, about half a block over the skim.
     */
    private const val RISES_AT_MOST = 0.08
    private const val RISES_WHILE_SKIMMING = 0.02
}
