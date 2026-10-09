package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelClock
import co.voik.ephemeris.sky.LevelLookPayload
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.properties.numeric.NeedleDirectionHelper
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.ItemOwner
import net.minecraft.world.item.ItemStack

/**
 * The time of day a clock item shows, as an item model property: `ephemeris:daytime`.
 *
 * Vanilla's clock reads the sun only in `minecraft:overworld` and spins everywhere else, so a level this
 * library describes has to be given a reading of its own. It is [LevelClock.clockHandFor] — the hour the
 * level is lit as — or the same random spin vanilla's fallback gives where there is nothing to tell the time by.
 */
class LevelDaytime(private val wobbles: Boolean) : NeedleDirectionHelper(wobbles), RangeSelectItemModelProperty {

    private val randomSource: RandomSource = RandomSource.create()
    private val wobbler = newWobbler(WOBBLE_RATE)

    override fun calculate(stack: ItemStack, level: ClientLevel, seed: Int, owner: ItemOwner): Float {
        val reading = LevelClock.clockHandFor(level) ?: randomSource.nextFloat()
        val gameTime = level.gameTime
        if (wobbler.shouldUpdate(gameTime)) wobbler.update(gameTime, reading)
        return wobbler.rotation()
    }

    override fun type(): MapCodec<LevelDaytime> = MAP_CODEC

    companion object {
        val ID: Identifier = Identifier.fromNamespaceAndPath(LevelLookPayload.NAMESPACE, "daytime")

        val MAP_CODEC: MapCodec<LevelDaytime> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.BOOL.optionalFieldOf("wobble", true).forGetter(LevelDaytime::wobbles),
            ).apply(instance, ::LevelDaytime)
        }

        /** Vanilla's own wobble for its clock. */
        private const val WOBBLE_RATE = 0.9f
    }
}
