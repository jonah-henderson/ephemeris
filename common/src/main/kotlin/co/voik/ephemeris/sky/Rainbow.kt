package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A bow standing opposite the sun, on the days it comes and in the weather that makes one.
 *
 * **Data, like everything else in a [SkySpec]** — it travels on the payload and is rebuilt on every open,
 * so nothing about how it is drawn belongs here.
 *
 * **Almost none of where it goes is written down, and that is the point.** A bow is not placed, it is
 * *implied*: it is a circle of [radiusDegrees] centred on the antisolar point, which is the direction
 * exactly opposite the light casting it. Everything downstream already knows where a body stands
 * ([CelestialPath.directionAt]), so an Age with two suns gets two bows in different quarters of the sky, a
 * moon casts a pale one, and an Age with no light at all has none — none of which is a case anything here
 * was taught.
 *
 * The same geometry is what makes a bow a morning and evening thing without a rule saying so. The antisolar
 * point sits as far below the horizon as the light sits above it, so the crown stands at
 * [crownAboveHorizon] and a light higher than [radiusDegrees] casts a bow entirely underground. See
 * [castAt].
 *
 * **Nothing here says whether it is *showing*.** How wet the air is and which body is up are the
 * renderer's to ask; keeping the two apart is what lets this be checked without a level.
 */
data class Rainbow(
    /**
     * The band, **outermost first** — red at the outside of the primary bow, violet at its inside, which is
     * the order a person names them in and the order the renderer lays them out.
     *
     * The secondary bow reverses this, as a real one does, so nothing needs to be written for it twice.
     */
    val colours: List<Rgba> = ORDINARY_SPECTRUM,
    /** How brightly it burns, against an ordinary one. */
    val glow: Float = ORDINARY_GLOW,
    /**
     * How wide the arc stands from the antisolar point, in degrees.
     *
     * **[WATERS_OWN] is what our own water does and an Age need not.** It is a fact about what the light is
     * bending through rather than about the sky, so a world whose rain is not water has every right to a
     * bow at thirty degrees or seventy — and because the crown stands at [radiusDegrees] less the light's
     * altitude, a wider bow is also one seen for more of the day.
     */
    val radiusDegrees: Float = WATERS_OWN,
    /** How thick the band is, in degrees. */
    val widthDegrees: Float = ORDINARY_WIDTH,
    /** Whether the fainter, wider, reversed second bow stands outside the first. */
    val secondary: Boolean = true,
    /**
     * How much falling water it needs, `0..1` — **one is a bow that will not come without rain and nought
     * is one that never wanted any**.
     *
     * A dial rather than a rule because an Age is written rather than observed. The honest default is one:
     * a bow is sunlight bent through rain, and an Age that names the phenomenon gets weather to match
     * (`Phenomenon.RAINBOW.insistsOn`). A writer who describes a bow into a dry clear sky is saying
     * something else on purpose, and this is where they say it.
     */
    val needsRain: Float = ORDINARY_RAIN_NEEDED,
    /** What share of days it comes at all, `0..1`. One is every day; nought is never. */
    val frequency: Float = ORDINARY_FREQUENCY,
    /** Which days it takes. Two levels alike still differ. */
    val seed: Long = 0L,
) {

    /**
     * The band as anything reading it should see it — [colours], or the ordinary one where a packet arrived
     * with none.
     *
     * A guard rather than a `require` in the constructor: an empty list is a thing a codec can deliver, and
     * a level that looks slightly wrong beats a client that throws while decoding a sky.
     */
    val band: List<Rgba> get() = colours.ifEmpty { ORDINARY_SPECTRUM }

    /** Where the second bow stands, keeping a real one's spacing however wide the first was written. */
    val secondaryRadiusDegrees: Float get() = radiusDegrees * SECONDARY_SPREAD

    /** How thick the second bow is. Wider than the first, as a real one is. */
    val secondaryWidthDegrees: Float get() = widthDegrees * SECONDARY_WIDENING

    /**
     * How strongly it comes on the [dayIndex]th day, `0..1` — nought on a day it does not come.
     *
     * The aurora's own rule, through [DayRoll], for the aurora's own reason.
     */
    fun strengthOn(dayIndex: Long): Float = DayRoll.strengthOf(seed, dayIndex, frequency, FAINTEST)

    /**
     * How high the crown of the bow stands above the horizon when its light is at [lightAltitudeDegrees].
     *
     * The whole of the geometry in one line: the antisolar point is as far below the horizon as the light
     * is above it, so the top of a circle of [radiusDegrees] about it stands at the difference. Negative is
     * a bow entirely underground.
     */
    fun crownAboveHorizon(lightAltitudeDegrees: Float): Float = radiusDegrees - lightAltitudeDegrees

    /**
     * How much of a bow a light standing at [lightAltitudeDegrees] casts, `0..1`.
     *
     * **A gate with a soft edge, not a dimming.** How large the bow is, is the geometry's business — a
     * climbing light sinks its own bow below the horizon and the arc shrinks to nothing on its own. All
     * this does is decline to draw one for a light that has set (there is no light to bend) or climbed past
     * its own [radiusDegrees] (there is no arc left above the ground), and taper the last degree of each so
     * neither edge arrives as a pop.
     */
    fun castAt(lightAltitudeDegrees: Float): Float {
        if (lightAltitudeDegrees <= -TAPER || lightAltitudeDegrees >= radiusDegrees) return NOTHING
        val risen = ((lightAltitudeDegrees + TAPER) / TAPER).coerceIn(NOTHING, 1.0f)
        val roomLeft = (crownAboveHorizon(lightAltitudeDegrees) / TAPER).coerceIn(NOTHING, 1.0f)
        return risen * roomLeft
    }

    /**
     * How much the air's [wetness] lets through, `0..1`, given what this bow asks of it.
     *
     * A bow needing nothing answers one whatever the weather; one needing everything answers the wetness
     * itself, and the dial reads between.
     */
    fun wetEnoughAt(wetness: Float): Float =
        (1.0f - needsRain) + needsRain * wetness.coerceIn(NOTHING, 1.0f)

    companion object {
        /**
         * What a bow nobody described looks like — **the real one**, outermost first.
         *
         * Held a little short of full saturation on purpose. A bow is added to the sky rather than laid
         * over it, so a saturated band against a bright day drives its strongest channel to one and comes
         * out white; pale is both what the blending can render and what a real bow looks like.
         */
        val ORDINARY_SPECTRUM: List<Rgba> = listOf(
            Rgba(0.86f, 0.30f, 0.24f),
            Rgba(0.92f, 0.58f, 0.24f),
            Rgba(0.90f, 0.86f, 0.35f),
            Rgba(0.36f, 0.78f, 0.42f),
            Rgba(0.26f, 0.48f, 0.86f),
            Rgba(0.50f, 0.32f, 0.78f),
        )

        /** What sunlight bent through water does, and the one radius that means "ours". */
        const val WATERS_OWN = 42.0f

        /** The second bow stands here times the first, which is where a real one stands. */
        const val SECONDARY_SPREAD = 51.0f / WATERS_OWN

        /** And is that much thicker than it, likewise. */
        const val SECONDARY_WIDENING = 1.6f

        /** How much of the light the second bow keeps. Twice reflected, so most of it is gone. */
        const val SECONDARY_KEEPS = 0.38f

        const val ORDINARY_GLOW = 1.0f

        /** Wide enough to read as a band of colour rather than a line. */
        const val ORDINARY_WIDTH = 2.4f

        /** A bow is rain bent by sun, and by default it wants both. */
        const val ORDINARY_RAIN_NEEDED = 1.0f

        /**
         * **High, deliberately, because the geometry is already the rarity.** A bow needs its light under
         * [WATERS_OWN] degrees and the air still wet, which rules out the middle of the day on its own; a
         * low roll on top of that would make one a thing nobody ever meets.
         */
        const val ORDINARY_FREQUENCY = 0.8f

        /** How faint the least of its days is. Above nothing, or a qualifying day would show nothing. */
        const val FAINTEST = 0.45f

        /** How many degrees of altitude each end of [castAt] is smoothed over. */
        private const val TAPER = 1.5f

        private const val NOTHING = 0.0f

        val CODEC: Codec<Rainbow> = RecordCodecBuilder.create { instance ->
            instance.group(
                Rgba.CODEC.listOf().optionalFieldOf("colours", ORDINARY_SPECTRUM).forGetter(Rainbow::colours),
                Codec.FLOAT.optionalFieldOf("glow", ORDINARY_GLOW).forGetter(Rainbow::glow),
                Codec.FLOAT.optionalFieldOf("radius", WATERS_OWN).forGetter(Rainbow::radiusDegrees),
                Codec.FLOAT.optionalFieldOf("width", ORDINARY_WIDTH).forGetter(Rainbow::widthDegrees),
                Codec.BOOL.optionalFieldOf("secondary", true).forGetter(Rainbow::secondary),
                Codec.FLOAT.optionalFieldOf("needs_rain", ORDINARY_RAIN_NEEDED).forGetter(Rainbow::needsRain),
                Codec.FLOAT.optionalFieldOf("frequency", ORDINARY_FREQUENCY).forGetter(Rainbow::frequency),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(Rainbow::seed),
            ).apply(instance, ::Rainbow)
        }
    }
}
