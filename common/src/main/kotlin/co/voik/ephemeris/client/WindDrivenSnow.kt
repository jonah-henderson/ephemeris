package co.voik.ephemeris.client

import co.voik.ephemeris.sky.Wind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.biome.Biome

/**
 * Snow thrown past on a level's wind: flakes around the player, more the harder it blows and the heavier it snows,
 * each sent along the wind and kept moving by [WindBorne.carry]. While the wind blows these are all the snow there is:
 * vanilla's columns, flakes drifting lazily under a texture that leaning only tilts, are not drawn.
 */
object WindDrivenSnow {

    /** This tick's flakes around [player] in [level], blowing as [blowing] says. */
    fun throwPast(level: ClientLevel, player: LocalPlayer, blowing: Wind.Blowing) {
        val snowing = level.getRainLevel(1.0f)
        val expected = snowing * (FLAKES_IN_ANY_WIND + blowing.strength * MORE_FLAKES_IN_THE_STRONGEST)
        if (expected <= 0.0f) return
        val random = level.random
        val cursor = BlockPos.MutableBlockPos()
        val hurry = blowing.strength * THROWN_AT_FULL_STRENGTH
        repeat(wholeNumberAround(expected, random.nextFloat())) {
            val x = player.x - blowing.x * UPWIND_BY + random.nextDouble() * AROUND * 2.0 - AROUND
            val y = player.eyeY + random.nextDouble() * (ABOVE + BELOW) - BELOW
            val z = player.z - blowing.z * UPWIND_BY + random.nextDouble() * AROUND * 2.0 - AROUND
            cursor.set(x, y, z)
            if (!level.canSeeSky(cursor) || !level.getBlockState(cursor).isAir) return@repeat
            if (level.getPrecipitationAt(cursor) != Biome.Precipitation.SNOW) return@repeat
            Minecraft.getInstance().particleEngine.createParticle(
                ParticleTypes.SNOWFLAKE,
                x,
                y,
                z,
                blowing.x * hurry,
                -FALLS * hurry,
                blowing.z * hurry,
            )
        }
    }

    /** A whole number of flakes whose mean is [expected], rounded up as often as its fraction says. */
    private fun wholeNumberAround(expected: Float, draw: Float): Int {
        val whole = expected.toInt()
        return whole + if (draw < expected - whole) 1 else 0
    }

    /**
     * How many flakes a tick in the heaviest snow in any wind, and how many more in the strongest: vanilla's columns
     * are not drawn while the wind blows, so these are all the snow there is.
     */
    private const val FLAKES_IN_ANY_WIND = 30.0f
    private const val MORE_FLAKES_IN_THE_STRONGEST = 50.0f

    /** How fast a flake is thrown along the wind in the strongest, in blocks a tick, and how much of that it falls. */
    private const val THROWN_AT_FULL_STRENGTH = 0.7
    private const val FALLS = 0.35

    /** Where flakes start: around the player, set back upwind so they cross the view, and mostly overhead. */
    private const val AROUND = 14.0
    private const val UPWIND_BY = 6.0
    private const val ABOVE = 10.0
    private const val BELOW = 4.0
}
