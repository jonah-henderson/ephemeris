package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelWind
import co.voik.ephemeris.sky.Wind
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
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.tags.BlockTags
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
        val carried = blowing.strength * CARRIED_AT_FULL_STRENGTH
        particle.move(blowing.x * carried, 0.0, blowing.z * carried)
    }

    /**
     * Debris off the ground around the player, in a wind of at least [LIFTS_DEBRIS_FROM]: snow off snow, and off
     * anything else a dust of its own colour, so sand blows as sand and a canopy sheds green. Each is a falling-dust
     * or snowflake particle kicked up from just over the ground, which [carry] then takes downwind as it settles.
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
            val ground = level.getBlockState(cursor.set(x, open - 1, z))
            if (ground.isAir) return@repeat
            val lifted: ParticleOptions = if (ground.`is`(BlockTags.SNOW)) ParticleTypes.SNOWFLAKE
            else BlockParticleOption(ParticleTypes.FALLING_DUST, ground)
            val particle = minecraft.particleEngine.createParticle(
                lifted,
                x + random.nextDouble(),
                open + random.nextDouble() * LIFTED_FROM_AS_HIGH_AS,
                z + random.nextDouble(),
                0.0,
                0.0,
                0.0,
            )
            val kick = KICKED_UP_AT_LEAST + random.nextDouble() * (KICKED_UP_AT_MOST - KICKED_UP_AT_LEAST)
            particle?.setParticleSpeed(0.0, kick, 0.0)
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

    /** How fast debris is kicked up, in blocks a tick: it rises a block or two before it settles. */
    private const val KICKED_UP_AT_LEAST = 0.05
    private const val KICKED_UP_AT_MOST = 0.12
}
