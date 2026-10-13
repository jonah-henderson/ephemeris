package co.voik.ephemeris.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * How a level's rain falls and sounds, as shares of vanilla's: [pace] of the speed every streak falls at, and
 * [pitch] of the pitch its patter is played at. Snow is left as it is.
 */
data class RainLook(val pace: Float = ORDINARY, val pitch: Float = ORDINARY) {

    val isOrdinary: Boolean get() = pace == ORDINARY && pitch == ORDINARY

    companion object {
        const val ORDINARY = 1.0f

        val CODEC: Codec<RainLook> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.optionalFieldOf("pace", ORDINARY).forGetter(RainLook::pace),
                Codec.FLOAT.optionalFieldOf("pitch", ORDINARY).forGetter(RainLook::pitch),
            ).apply(instance, ::RainLook)
        }
    }
}
