package co.voik.ephemeris.sky

import co.voik.ephemeris.Oklch
import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A colour turning round the colour wheel and back to itself every [periodTicks] of game time, starting [offset] of
 * the way round — so two cycles with the same period and offset turn in step, and any other pair on clocks of their
 * own.
 *
 * Turning a hue does nothing to a grey, so a pale colour is also drawn up to [tinge] of the way toward a pastel of the
 * hue the turn stands at, by as much as it lacks colour of its own: a green leaf turns, a white cloud takes on a passing
 * pastel.
 */
data class HueCycle(val periodTicks: Int, val offset: Float = 0.0f, val tinge: Float = 0.0f) {

    /** How far round the wheel this stands at [gameTime], `0..1`. */
    fun turnAt(gameTime: Long, partialTick: Float = 0.0f): Float {
        val period = periodTicks.coerceAtLeast(1).toDouble()
        val turned = ((gameTime % periodTicks.coerceAtLeast(1)) + partialTick) / period + offset
        return (turned - kotlin.math.floor(turned)).toFloat()
    }

    /** [colour] as it stands at [gameTime]: its hue turned, and tinged toward the turn's hue where it is pale. */
    fun over(colour: Rgba, gameTime: Long, partialTick: Float = 0.0f): Rgba = at(colour, turnAt(gameTime, partialTick))

    /**
     * [colour] at [turn] of the way round, in [Oklch] so it looks as light at every turn. A pale colour is drawn toward
     * a pastel of the turn's hue by [tinge] and by as much as it lacks chroma of its own, and held a little under
     * white, which has no room for any colour.
     */
    fun at(colour: Rgba, turn: Float): Rgba {
        val own = Oklch.of(colour)
        val turned = own.turned(turn)
        val drawn = tinge * (1.0f - (own.chroma / VIVID_CHROMA).coerceAtMost(1.0f))
        if (drawn <= 0.0f) return turned.toRgba(colour.alpha)
        val pastel = Oklch(own.lightness.coerceAtMost(LIGHTEST_TINGED), TINGED_CHROMA, REDS_HUE + turn)
        return turned.towards(pastel, drawn).toRgba(colour.alpha)
    }

    companion object {
        /** The chroma a colour counts as fully vivid at, and so takes no tinge. */
        private const val VIVID_CHROMA = 0.15f

        /** How vivid a tinged colour's pastel is, and how light it may be at the most. */
        private const val TINGED_CHROMA = 0.12f
        private const val LIGHTEST_TINGED = 0.9f

        /** Where red stands on OKLCH's wheel, so a cycle starting at nought tinges toward red as RGB's would. */
        private const val REDS_HUE = 0.081f

        val CODEC: Codec<HueCycle> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("period_ticks").forGetter(HueCycle::periodTicks),
                Codec.FLOAT.optionalFieldOf("offset", 0.0f).forGetter(HueCycle::offset),
                Codec.FLOAT.optionalFieldOf("tinge", 0.0f).forGetter(HueCycle::tinge),
            ).apply(instance, ::HueCycle)
        }
    }
}

/**
 * Which of a level's colours turn ([HueCycle]), each on its own cycle: the sky, the fog, the clouds (vanilla's and the
 * decks), the light, the water's fog, the stars, the aurora, and each body by its index in the sky; and in the chunk
 * mesh ([co.voik.ephemeris.client.TurningTerrain]), the [ground]'s tinted blocks — grass, leaves, anything a biome
 * tints on land — and the [water]'s surface, which a shader can tell apart only by the layer they are drawn in.
 */
data class ColourCycles(
    val sky: HueCycle? = null,
    val fog: HueCycle? = null,
    val cloud: HueCycle? = null,
    val light: HueCycle? = null,
    val waterFog: HueCycle? = null,
    val stars: HueCycle? = null,
    val aurora: HueCycle? = null,
    val bodies: Map<Int, HueCycle> = emptyMap(),
    val ground: HueCycle? = null,
    val water: HueCycle? = null,
) {
    val isNone: Boolean
        get() = sky == null && fog == null && cloud == null && light == null && waterFog == null &&
            stars == null && aurora == null && bodies.isEmpty() && ground == null && water == null

    /** Whether anything in the sky itself turns, which vanilla's own sky cannot draw. */
    val turnsTheSky: Boolean get() = stars != null || bodies.isNotEmpty()

    companion object {
        val NONE = ColourCycles()

        private val BODY_INDEX: Codec<Int> = Codec.STRING.xmap(String::toInt, Int::toString)

        private fun named(name: String, getter: (ColourCycles) -> HueCycle?): RecordCodecBuilder<ColourCycles, java.util.Optional<HueCycle>> =
            HueCycle.CODEC.optionalFieldOf(name).forGetter { java.util.Optional.ofNullable(getter(it)) }

        val CODEC: Codec<ColourCycles> = RecordCodecBuilder.create { instance ->
            instance.group(
                named("sky", ColourCycles::sky),
                named("fog", ColourCycles::fog),
                named("cloud", ColourCycles::cloud),
                named("light", ColourCycles::light),
                named("water_fog", ColourCycles::waterFog),
                named("stars", ColourCycles::stars),
                named("aurora", ColourCycles::aurora),
                Codec.unboundedMap(BODY_INDEX, HueCycle.CODEC).optionalFieldOf("bodies", emptyMap())
                    .forGetter(ColourCycles::bodies),
                named("ground", ColourCycles::ground),
                named("water", ColourCycles::water),
            ).apply(instance) { sky, fog, cloud, light, waterFog, stars, aurora, bodies, ground, water ->
                ColourCycles(
                    sky.orElse(null), fog.orElse(null), cloud.orElse(null), light.orElse(null),
                    waterFog.orElse(null), stars.orElse(null), aurora.orElse(null), bodies,
                    ground.orElse(null), water.orElse(null),
                )
            }
        }
    }
}
