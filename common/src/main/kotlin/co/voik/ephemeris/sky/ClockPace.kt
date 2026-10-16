package co.voik.ephemeris.sky

/**
 * How fast a level's own clock runs, read off its sky — Ephemeris' answer to what a body slowing or stopping
 * means for the day ([co.voik.ephemeris.LevelClocks]).
 *
 * **The clock always runs.** A body that stands still keeps one face to the world — tidally locked — and is not
 * time stopped, so the day count, sleep and everything vanilla keys to the clock go on.
 *
 * **The day is kept by a reference body**: the first of the largest moving suns, or of the largest moving moons
 * where no sun moves. Its period is the clock's, so a slow reference is a slow day — the light, the colours, the
 * day count and sleep all together — and every other body is measured against that clock ([clocked]). Where
 * nothing moves there is nothing to keep time by, and the clock runs at the overworld's pace.
 */
data class ClockPace(val rate: Float) {

    companion object {
        val ORDINARY = ClockPace(rate = 1.0f)

        fun of(sky: SkySpec): ClockPace {
            val period = referenceOf(sky)?.path?.periodTicks ?: return ORDINARY
            if (period <= 0 || period == Orbit.TICKS_PER_VANILLA_DAY) return ORDINARY
            return ClockPace(rate = Orbit.TICKS_PER_VANILLA_DAY.toFloat() / period)
        }

        /** The body [sky]'s clock keeps time by, or null where nothing in it moves. */
        fun referenceOf(sky: SkySpec): CelestialBody? {
            val moving = sky.bodies.filterNot { it.path.isStill }
            val (suns, moons) = moving.partition { it.phase == null }
            return largestFirst(suns) ?: largestFirst(moons)
        }

        /** The largest of [bodies], the first of them where several are as large. */
        private fun largestFirst(bodies: List<CelestialBody>): CelestialBody? {
            val largest = bodies.maxOfOrNull { it.appearance.angularSize } ?: return null
            return bodies.first { it.appearance.angularSize == largest }
        }
    }
}

/**
 * This sky measured against a clock running at its own [ClockPace]: every body's period taken in the clock's
 * ticks rather than the world's, so the reference body keeps vanilla's day on a clock that runs slow or fast. A
 * sky already measured so is itself, since its reference then has vanilla's period.
 */
fun SkySpec.clocked(): SkySpec {
    val pace = ClockPace.of(this)
    if (pace == ClockPace.ORDINARY) return this
    return copy(bodies = bodies.map { it.copy(path = it.path.paced(pace.rate.toDouble())) })
}
