package co.voik.ephemeris.sky

/**
 * Where a star field stands as it wheels at a pace of its own ([StarField.pace]): vanilla's angle, which turns once a
 * day with the sun, plus a turn for every day the clock has run times how much faster or slower the field goes. Worked
 * out off the whole clock rather than by scaling vanilla's angle, which wraps each dawn and would jump the field there
 * at any pace but a whole one.
 */
object StarWheel {

    /** The field's angle in radians at [clockTime], where vanilla's is [vanillasAngle]. */
    fun angleAt(vanillasAngle: Float, pace: Float, clockTime: Long): Float = when (pace) {
        StarField.VANILLAS_PACE -> vanillasAngle
        HELD -> HELD_AT_MIDNIGHT
        else -> {
            val extraTurns = (pace - StarField.VANILLAS_PACE).toDouble() * clockTime / TICKS_PER_DAY
            val turned = vanillasAngle + (extraTurns - kotlin.math.floor(extraTurns)) * FULL_TURN
            (turned % FULL_TURN).toFloat()
        }
    }

    private const val HELD = 0.0f

    /** Where a field held still stands: as vanilla's stands at midnight, half a turn from its noon. */
    private const val HELD_AT_MIDNIGHT = Math.PI.toFloat()

    private const val TICKS_PER_DAY = 24_000.0
    private const val FULL_TURN = 2.0 * Math.PI
}
