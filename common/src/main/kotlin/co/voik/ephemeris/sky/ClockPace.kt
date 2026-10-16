package co.voik.ephemeris.sky

/**
 * How fast a level's own clock runs, and whether it runs at all, read off its sky — Ephemeris' answer to what a
 * body stopping or slowing means for the day ([co.voik.ephemeris.LevelClocks]).
 *
 * **The day is the first sun.** Its period is the clock's, so a slow first sun is a slow day — the light, the
 * colours, the day count and sleep all together, nothing left on another schedule — and every other body is
 * measured against that clock ([clocked]). **Every body stopped is a stopped clock.** A first sun stopped beneath
 * bodies that still move leaves the clock running and the hour held where that sun stands
 * ([LevelClock.vanillaEquivalent]). A sky with no sun keeps the ordinary pace.
 */
data class ClockPace(val rate: Float, val paused: Boolean) {

    companion object {
        val ORDINARY = ClockPace(rate = 1.0f, paused = false)

        fun of(sky: SkySpec): ClockPace {
            val bodies = sky.bodies
            if (bodies.isNotEmpty() && bodies.all { it.path.isStill }) return ORDINARY.copy(paused = true)
            val first = bodies.firstOrNull { it.phase == null } ?: return ORDINARY
            val period = first.path.periodTicks
            if (first.path.isStill || period <= 0 || period == Orbit.TICKS_PER_VANILLA_DAY) return ORDINARY
            return ORDINARY.copy(rate = Orbit.TICKS_PER_VANILLA_DAY.toFloat() / period)
        }
    }
}

/**
 * This sky measured against a clock running at its own [ClockPace]: every body's period taken in the clock's
 * ticks rather than the world's, so the first sun keeps vanilla's day on a clock that runs slow or fast. A sky
 * already measured so is itself, since its first sun then has vanilla's period.
 */
fun SkySpec.clocked(): SkySpec {
    val pace = ClockPace.of(this)
    if (pace.rate == ClockPace.ORDINARY.rate) return this
    return copy(bodies = bodies.map { it.copy(path = it.path.paced(pace.rate.toDouble())) })
}
