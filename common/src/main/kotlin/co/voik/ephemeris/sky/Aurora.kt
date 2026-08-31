package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A curtain of light standing in a level's sky, on the nights it comes.
 *
 * **Data, like everything else in a [SkySpec]** — it travels on the payload and is rebuilt on every open,
 * so nothing about how it is drawn belongs here.
 *
 * Two things about it are load-bearing and easy to get backwards.
 *
 * - **[colours] is a ramp read from the crown down.** The first is the colour at the top of the curtain and
 *   the last the colour at its hem, which is the order a person names them in and the order the renderer's
 *   ramp texture is built in. One colour is a ramp of one, and is the ordinary case.
 * - **Nothing here says whether it is *showing*.** [strengthOn] answers only whether tonight is one of its
 *   nights; how bright it stands at this instant, and whether the ground below is cold enough to see it at
 *   all, are the renderer's to ask. Keeping the two apart is what lets this be checked without a level.
 */
data class Aurora(
    /** What it burns, **crown first**. Never empty; a ramp of one is a curtain of one colour. */
    val colours: List<Rgba> = ORDINARY_RAMP,
    /** How brightly it burns, against an ordinary one. */
    val glow: Float = ORDINARY_GLOW,
    /** How much of the sky it crosses, `0..1`. */
    val breadth: Float = ORDINARY_BREADTH,
    /** How tall the curtain stands, `0..1`. */
    val height: Float = ORDINARY_HEIGHT,
    /**
     * How many curtains hang at once — **each waxing and waning on its own**, so the sky fills and empties
     * through a night rather than holding one arc all the way to dawn.
     *
     * A real display is several arcs at different heights, and they are not in step: one brightens as
     * another dies. That is the whole reason this is a count rather than a wider single band, and why the
     * renderer gives each its own phase, height and lift instead of drawing the same curtain twice.
     */
    val curtains: Int = ORDINARY_CURTAINS,
    /** What share of nights it comes at all, `0..1`. One is every night; nought is never. */
    val frequency: Float = ORDINARY_FREQUENCY,
    /** Which way the band crosses the sky, in degrees clockwise from north. */
    val bearingDegrees: Float = 0.0f,
    /**
     * The ground it may be seen over.
     *
     * **[AuroraGround.ANYWHERE] by default, and deliberately the neutral answer rather than the realistic
     * one.** A library whose aurora silently never comes is a worse thing to meet first than one that comes
     * everywhere, and a consumer who wants the polar rule is one field away from it. The realism this
     * feature is aimed at lives in [ORDINARY_RAMP], where a default can be seen.
     */
    val ground: AuroraGround = AuroraGround.ANYWHERE,
    /** Which nights it takes and which way its folds lie. Two levels alike still differ. */
    val seed: Long = 0L,
) {

    /**
     * The ramp as anything reading it should see it — [colours], or the ordinary one where a packet arrived
     * with none.
     *
     * A guard rather than a `require` in the constructor: an empty list is a thing a codec can deliver and
     * a level that looks slightly wrong beats a client that throws while decoding a sky.
     */
    val ramp: List<Rgba> get() = colours.ifEmpty { ORDINARY_RAMP }

    /**
     * How strongly it comes on the [dayIndex]th day, `0..1` — **nought on a night it does not come**.
     *
     * [DayRoll] carries the whole of it, and why a rare aurora is usually a faint one.
     */
    fun strengthOn(dayIndex: Long): Float = DayRoll.strengthOf(seed, dayIndex, frequency, FAINTEST)

    companion object {
        /**
         * What an aurora nobody described looks like — **the real one**, which is the point of the whole
         * feature: a faint red crown, green through the body, a violet hem.
         *
         * Green in the middle because that is where an aurora's green is, and because a ramp read at the
         * curtain's middle is what the eye takes for its colour.
         */
        val ORDINARY_RAMP: List<Rgba> = listOf(
            Rgba(0.85f, 0.22f, 0.30f),
            Rgba(0.25f, 0.95f, 0.55f),
            Rgba(0.45f, 0.30f, 0.85f),
        )

        const val ORDINARY_GLOW = 1.0f

        /** Rather more than half the sky, which is what a band crossing it looks like from underneath. */
        const val ORDINARY_BREADTH = 0.7f

        /** Tall by default: height is most of what sells a form standing hundreds of kilometres up. */
        const val ORDINARY_HEIGHT = 0.78f

        /** Enough that the sky has something going on in it without becoming a ceiling. */
        const val ORDINARY_CURTAINS = 3

        /** The most that will be drawn, each being a pass of its own. */
        const val MOST_CURTAINS = 6

        /** About one night in three, which is often enough to be a feature of the Age and not of the week. */
        const val ORDINARY_FREQUENCY = 0.35f

        /** How faint the least of its nights is. Above nothing, or a qualifying night would show nothing. */
        const val FAINTEST = 0.35f

        val CODEC: Codec<Aurora> = RecordCodecBuilder.create { instance ->
            instance.group(
                // **A list, and any length.** A fixed few would have put a cap on how many colours a
                // curtain may burn, and the renderer builds a ramp texture rather than filling uniform
                // slots precisely so there is no number to choose here.
                Rgba.CODEC.listOf().optionalFieldOf("colours", ORDINARY_RAMP).forGetter(Aurora::colours),
                Codec.FLOAT.optionalFieldOf("glow", ORDINARY_GLOW).forGetter(Aurora::glow),
                Codec.FLOAT.optionalFieldOf("breadth", ORDINARY_BREADTH).forGetter(Aurora::breadth),
                Codec.FLOAT.optionalFieldOf("height", ORDINARY_HEIGHT).forGetter(Aurora::height),
                Codec.INT.optionalFieldOf("curtains", ORDINARY_CURTAINS).forGetter(Aurora::curtains),
                Codec.FLOAT.optionalFieldOf("frequency", ORDINARY_FREQUENCY).forGetter(Aurora::frequency),
                Codec.FLOAT.optionalFieldOf("bearing", 0.0f).forGetter(Aurora::bearingDegrees),
                AuroraGround.CODEC.optionalFieldOf("ground", AuroraGround.ANYWHERE).forGetter(Aurora::ground),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(Aurora::seed),
            ).apply(instance, ::Aurora)
        }
    }
}
