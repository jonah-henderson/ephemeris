package co.voik.ephemeris.sky

/**
 * Where everything in a sky stands at one instant, and what that means for the level underneath.
 *
 * **A pure function of the spec and the clock**, which is what lets both sides ask it and get the same
 * answer without anything crossing the wire. A client already holds the whole [SkySpec] and its own level's
 * day time; so does the server. Sending a "it is daytime" packet would be sending something both ends can
 * derive, and giving them a chance to disagree.
 *
 * This is the report half of the library's day and night: a consumer that wants to decide for itself reads
 * this and does as it likes, and [Daylight.VANILLA_CLOCK] leaves vanilla's rules alone while it does.
 */
data class SkyReading(val bodies: List<BodyReading>, val dayTime: Long) {

    /** Every body with no phase — the working definition of a sun, here and in the renderer. */
    val suns: List<BodyReading> get() = bodies.filter { it.isSun }

    val moons: List<BodyReading> get() = bodies.filterNot { it.isSun }

    /** Whether any sun at all is above the horizon. */
    val anySunIsUp: Boolean get() = suns.any { it.isUp }

    /** The sun standing highest, or null when every one of them has set. */
    val highestSun: BodyReading? get() = suns.filter { it.isUp }.maxByOrNull { it.altitudeDegrees }

    /** The body nearest the horizon either way — what a single-glow sky paints from. */
    val nearestTheHorizon: BodyReading? get() = suns.minByOrNull { Math.abs(it.altitudeDegrees) }

    /** Whether it is daytime under [rules]. Null where the rules decline to say — see [Daylight.VANILLA_CLOCK]. */
    fun isDaytime(rules: SkyRules): Boolean? = when (rules.daylight) {
        Daylight.EVERY_SUN -> anySunIsUp
        Daylight.PRIMARY_SUN -> primaryUnder(rules)?.isUp ?: anySunIsUp
        Daylight.VANILLA_CLOCK -> null
    }

    /**
     * The body the rules named, or null where the spec has none.
     *
     * An index past the end falls back to the first body rather than throwing: a spec that loses a sun
     * should degrade to something sensible, not take a tick down with it.
     */
    fun primaryUnder(rules: SkyRules): BodyReading? =
        bodies.getOrNull(rules.primaryBody) ?: bodies.firstOrNull()

    companion object {
        fun of(spec: SkySpec, dayTime: Long): SkyReading = SkyReading(
            spec.bodies.mapIndexed { index, body -> BodyReading.of(index, body, dayTime) },
            dayTime,
        )
    }
}

/** Where one body stands, and enough about it to place a glow or answer a question. */
data class BodyReading(
    val index: Int,
    val body: CelestialBody,
    /** Degrees above the horizon; negative is below. */
    val altitudeDegrees: Float,
    /** Degrees clockwise from north. */
    val azimuthDegrees: Float,
) {
    val isUp: Boolean get() = altitudeDegrees >= 0.0f

    /** A sun is a body that does not wax and wane. Moons have phases; suns do not. */
    val isSun: Boolean get() = body.phase == null

    /** Never sets, so a level following it is in permanent day. */
    val staysUp: Boolean get() = body.orbit.staysUp

    val neverRises: Boolean get() = body.orbit.neverRises

    /**
     * How near the horizon this body is, `0..1`, for weighting a glow — 1 exactly on it, falling to 0 by
     * [GLOW_REACH] either side.
     *
     * Both sides of the horizon count, which is the point: the sky is at its reddest when the sun has just
     * gone, not while it is still up.
     */
    val horizonNearness: Float
        get() = (1.0f - Math.abs(altitudeDegrees) / GLOW_REACH).coerceIn(0.0f, 1.0f)

    companion object {
        /**
         * How far above or below the horizon a body still colours it, in degrees.
         *
         * Vanilla's own sunrise band runs about this wide — its `getSunriseColor` fades out once the sun is
         * roughly a fifth of a turn from the horizon — so a single sun on vanilla's path lights the sky for
         * about as long as it always did.
         */
        const val GLOW_REACH = 22.0f

        fun of(index: Int, body: CelestialBody, dayTime: Long): BodyReading = BodyReading(
            index,
            body,
            body.orbit.altitudeAt(dayTime),
            body.orbit.azimuthAt(dayTime),
        )
    }
}

/**
 * When a body next crosses the horizon, and which way.
 *
 * **Found by walking the path rather than by solving it.** The closed form is a trigonometric identity three
 * rotations deep — easy to get subtly wrong, impossible to read, and it has to special-case every path that
 * never crosses at all. Walking is exact to within a step, says plainly what it means, and is asked for at
 * human rates rather than per frame.
 */
data class HorizonCrossing(val dayTime: Long, val rising: Boolean) {
    companion object {
        /**
         * The next crossing strictly after [after], or **null** for a body that never crosses — one that
         * stays up, or one that never rises.
         */
        fun next(orbit: Orbit, after: Long): HorizonCrossing? {
            if (orbit.staysUp || orbit.neverRises) return null
            var previousTime = after
            var previous = orbit.altitudeAt(previousTime) >= 0.0f
            // One full period is enough by definition: a path that crosses at all crosses within one turn.
            val end = after + orbit.periodTicks
            var time = after + STEP
            while (time <= end) {
                val up = orbit.altitudeAt(time) >= 0.0f
                if (up != previous) return HorizonCrossing(narrow(orbit, previousTime, time, previous), up)
                previousTime = time
                previous = up
                time += STEP
            }
            return null
        }

        /** Bisect the step the crossing fell inside, down to the tick. */
        private fun narrow(orbit: Orbit, before: Long, after: Long, wasUp: Boolean): Long {
            var low = before
            var high = after
            while (high - low > 1) {
                val middle = low + (high - low) / 2
                if ((orbit.altitudeAt(middle) >= 0.0f) == wasUp) low = middle else high = middle
            }
            return high
        }

        /** Ticks between samples. Fine enough that no body of any period can cross and return inside one. */
        private const val STEP = 20L
    }
}
