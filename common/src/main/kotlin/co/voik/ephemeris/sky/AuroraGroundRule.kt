package co.voik.ephemeris.sky

import net.minecraft.core.BlockPos
import net.minecraft.world.level.LevelReader

/**
 * Whether the ground at a position is cool enough for a curtain — [Aurora.warmestGround] asked of a level.
 *
 * **One reader for both sides.** The client samples a ring around the camera and fades between answers
 * (`GroundWarmth`), and the server answers the same question for `/age showing`; if the two spelled the
 * rule out separately, a disagreement between them would read as a rendering fault rather than as what it
 * was. What they share is exactly this line.
 *
 * It is also the one thing that needs vanilla's *number* rather than one of its two fixed lines, so the
 * access widener that buys it is stated once here rather than in every consumer.
 */
object AuroraGroundRule {

    /**
     * **The height-adjusted figure, under the cache rather than through it.** 26.2 deprecated
     * `getTemperature`, which is a thread-local cache wrapped around `getHeightAdjustedTemperature` and
     * nothing else — so this asks the one underneath and gets the same number undeprecated. What vanilla
     * leaves public is a pair of fixed questions, `coldEnoughToSnow` at 0.15 and `warmEnoughToRain` above
     * 0.1, and a curtain's ceiling is a number a writer chose, so neither line can stand in for it.
     */
    fun isCoolEnough(level: LevelReader, at: BlockPos, warmest: Float): Boolean =
        level.getBiome(at).value().getHeightAdjustedTemperature(at, level.seaLevel) <= warmest
}
