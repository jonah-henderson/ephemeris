package co.voik.ephemeris.sky

/**
 * Which days a sky thing comes on — **arithmetic both ends can do**, so nothing about it is sent,
 * persisted or reconciled.
 *
 * Shared by [Aurora] and [Rainbow] because it is one idea and not two: something that comes on some days
 * and not others needs a number every client agrees on and none of them has to be given. That is the whole
 * of why neither needs a server tick.
 *
 * An integer mix rather than a `RandomSource`: this is asked every frame by a renderer, and building a
 * generator to take one number out of it is a cost with nothing to show for it.
 */
object DayRoll {

    /**
     * How strongly something of this [frequency] comes on the [dayIndex]th day, `0..1` — **nought on a day
     * it does not come**, and never less than [faintest] on one it does.
     *
     * **A day that barely qualified is a faint one.** Testing the roll and returning a constant would make
     * [frequency] a switch — every occurrence identical on the days it came — where reading *how far
     * inside* its own threshold the roll landed gives a spread for free, and gives a rare thing the decency
     * of usually being a faint one.
     */
    fun strengthOf(seed: Long, dayIndex: Long, frequency: Float, faintest: Float): Float {
        if (frequency <= NEVER) return NOTHING
        val rolled = on(seed, dayIndex)
        if (rolled >= frequency) return NOTHING
        val howFarInside = 1.0f - rolled / frequency
        return faintest + (1.0f - faintest) * howFarInside
    }

    /** This day's roll, in `0.0..1.0` — stable for as long as the seed and the day are what they are. */
    fun on(seed: Long, dayIndex: Long): Float {
        var bits = (seed xor (dayIndex * DAYS_APART)) * MIX_ONE
        bits = (bits xor (bits ushr 33)) * MIX_TWO
        bits = bits xor (bits ushr 29)
        return ((bits ushr 40).toFloat() / (1 shl 24).toFloat()).coerceIn(NOTHING, 1.0f)
    }

    private const val NOTHING = 0.0f
    private const val NEVER = 0.0f

    /** Spreads consecutive days apart before the mix, so day follows day rather than tracking it. */
    private const val DAYS_APART = 0x2545F4914F6CDD1DL

    private const val MIX_ONE = -0x61c8864680b583ebL
    private const val MIX_TWO = -0x40a7b892e31b1a47L
}
