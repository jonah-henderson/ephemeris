package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import io.kotest.core.spec.style.FunSpec
import net.minecraft.nbt.NbtOps

/**
 * That a level's clock is read off its bodies as [ClockPace] says — kept by the first of the largest moving suns,
 * or moons, and at the overworld's pace where nothing moves — and that a sun held still is lit as the hour its
 * place says.
 */
class ClockPaceCheck : FunSpec({

    val day = Orbit.TICKS_PER_VANILLA_DAY

    fun sun(path: CelestialPath) = CelestialBody(path, Appearance.Sprite(Rgba.WHITE, 30.0f, Appearance.SUN_SHAPES))
    fun moon(path: CelestialPath) = sun(path).copy(phase = PhaseCycle(day * 8, 0, 8))
    fun skyOf(vararg bodies: CelestialBody) = SkySpec(bodies.toList(), StarField(1500, 0L))
    fun sized(body: CelestialBody, size: Float) = (body.appearance as Appearance.Sprite).copy(angularSize = size)

    test("an ordinary sky keeps the ordinary pace") {
        val pace = ClockPace.of(skyOf(sun(Orbit.VANILLA_SUN), moon(Orbit.VANILLA_MOON)))
        check(pace == ClockPace.ORDINARY) { "vanilla's own sky ran its clock at $pace" }
    }

    test("a reference sun twice as slow halves the clock, and is drawn on vanilla's day against it") {
        val slow = skyOf(sun(Orbit.VANILLA_SUN.paced(2.0)), moon(Orbit.VANILLA_MOON))
        val pace = ClockPace.of(slow)
        check(pace.rate == 0.5f) { "a sun with a two-day day ran the clock at $pace" }
        val clocked = slow.clocked()
        check(clocked.bodies.first().path.periodTicks == day) {
            "against the slow clock the sun took ${clocked.bodies.first().path.periodTicks} ticks, not a day"
        }
        check(clocked.bodies.last().path.periodTicks == day / 2) {
            "the moon kept ${clocked.bodies.last().path.periodTicks} clock ticks where a half-speed clock halves it"
        }
        check(clocked.clocked() == clocked) { "measuring an already measured sky changed it again" }
    }

    test("the clock keeps time by the first of the largest moving suns") {
        val small = sun(Orbit.VANILLA_SUN.paced(3.0)).let { it.copy(appearance = sized(it, 10.0f)) }
        val large = sun(Orbit.VANILLA_SUN.paced(2.0)).let { it.copy(appearance = sized(it, 40.0f)) }
        val alsoLarge = sun(Orbit.VANILLA_SUN.paced(4.0)).let { it.copy(appearance = sized(it, 40.0f)) }
        val pace = ClockPace.of(skyOf(small, large, alsoLarge))
        check(pace.rate == 0.5f) { "the clock kept time by the wrong sun: $pace" }
        val stillLargest = sun(Held(Orbit.VANILLA_SUN, 6000L)).let { it.copy(appearance = sized(it, 90.0f)) }
        check(ClockPace.of(skyOf(stillLargest, large)).rate == 0.5f) { "a still sun kept the clock's time" }
    }

    test("with no sun moving the moon keeps time, and with nothing moving the overworld's pace does") {
        val slowMoon = moon(Orbit.VANILLA_MOON.paced(2.0))
        val bySun = ClockPace.of(skyOf(sun(Held(Orbit.VANILLA_SUN, 6000L)), slowMoon))
        check(bySun.rate == 0.5f) { "under a still sun the slow moon did not keep the clock's time: $bySun" }
        val nothing = ClockPace.of(skyOf(sun(Held(Orbit.VANILLA_SUN, 6000L)), moon(Held(Orbit.VANILLA_MOON, 6000L))))
        check(nothing == ClockPace.ORDINARY) { "a sky where nothing moves ran its clock at $nothing" }
    }

    test("a held path stands still where its path put it, and says so on the wire") {
        val held = Held(Orbit.VANILLA_SUN, 3000L)
        val there = Orbit.VANILLA_SUN.altitudeAt(3000L)
        check(held.isStill && held.altitudeAt(0L) == there && held.altitudeAt(17_000L) == there) {
            "a held sun moved: ${held.altitudeAt(0L)} and ${held.altitudeAt(17_000L)}, held at $there"
        }
        check(held.risings().isEmpty()) { "a held sun rose" }
        val written = CelestialPath.CODEC.encodeStart(NbtOps.INSTANCE, held).getOrThrow()
        val read = CelestialPath.CODEC.parse(NbtOps.INSTANCE, written).getOrThrow()
        check(read == held) { "a held path came back as $read" }
    }

    test("a sun held in the morning is lit as morning, and one held in the afternoon as afternoon") {
        fun litAs(heldAt: Long): Long? {
            val look = LevelLook(skyOf(sun(Held(Orbit.VANILLA_SUN, heldAt))))
            return LevelClock.vanillaEquivalent(look, 0L)
        }
        val morning = litAs(3000L)
        val afternoon = litAs(9000L)
        check(morning != null && morning in 0L..<6000L) { "a sun held at 3000 was lit as $morning" }
        check(afternoon != null && afternoon in 6000L..<12_000L) { "a sun held at 9000 was lit as $afternoon" }
    }
})
