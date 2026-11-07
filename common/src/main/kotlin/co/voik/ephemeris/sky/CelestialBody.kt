package co.voik.ephemeris.sky

import co.voik.ephemeris.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.level.MoonPhase

/**
 * One sun or moon: where it goes ([path]), what it looks like ([appearance]), and whether it waxes and wanes
 * ([phase]).
 *
 * Separate on purpose: appearance is the part most likely to change when the mod grows a texture
 * pipeline, and a phase is a property only some bodies have, so [Appearance] can gain a variant without
 * the orbital maths or the codec shape noticing.
 */
data class CelestialBody(
    val path: CelestialPath,
    val appearance: Appearance,
    /** Null for a body that never changes, which is every sun. */
    val phase: PhaseCycle? = null,
    /** How the sprite meets the sky behind it. Vanilla's way by default — see [Blending]. */
    val blending: Blending = Blending.ADDS,
    /**
     * How far toward night this body holds the day while it is the sun the level follows, `0..1`: none by default, a
     * dusky noon at a third, night's own dark at one. The disc and the air dim with it.
     */
    val dimming: Float = 0.0f,
    /**
     * How much this body dazzles, `0..1`, where it is the sun the level follows: a halo round it, a glare over the
     * view facing it, and its light lingering after it sets — the game's own light being already the most there is.
     */
    val glare: Float = 0.0f,
) {
    companion object {
        val CODEC: Codec<CelestialBody> = RecordCodecBuilder.create { instance ->
            instance.group(
                CelestialPath.CODEC.fieldOf("orbit").forGetter(CelestialBody::path),
                Appearance.CODEC.fieldOf("appearance").forGetter(CelestialBody::appearance),
                PhaseCycle.CODEC.optionalFieldOf("phase").forGetter { body -> java.util.Optional.ofNullable(body.phase) },
                Blending.CODEC.optionalFieldOf("blending", Blending.ADDS).forGetter(CelestialBody::blending),
                Codec.FLOAT.optionalFieldOf("dimming", 0.0f).forGetter(CelestialBody::dimming),
                Codec.FLOAT.optionalFieldOf("glare", 0.0f).forGetter(CelestialBody::glare),
            ).apply(instance) { path, appearance, phase, blending, dimming, glare ->
                CelestialBody(path, appearance, phase.orElse(null), blending, dimming, glare)
            }
        }
    }
}

/**
 * What a body looks like.
 *
 * **[Sprite] borrows vanilla's own celestial sprites** — the sun and the eight moon shapes are all stitched
 * into the `minecraft:celestials` atlas, so this needs no asset directory, no access widener and no atlas
 * of our own. It also buys **real crescents**: a phase is a *shape*, and under additive blending a flat
 * disc has nothing to subtract darkness with, so borrowing vanilla's shapes gets it for free.
 *
 * One consequence: a resource pack that retextures the moon retextures ours too.
 *
 * **Sealed** with a dispatching codec even at one variant, since a new one is a key and a [MapCodec] and
 * the renderer batches by vertex format.
 */
sealed interface Appearance {
    val tint: Rgba

    /** Half-extent of the body as drawn, in the same units as [Orbit.distance]. Vanilla's sun is 30, moon 20. */
    val angularSize: Float

    /** The codec dispatch key. Adding a variant means adding a key and a [MapCodec], nothing more. */
    val kindKey: String

    /**
     * A quad drawn from the celestials atlas, tinted. [shapes] is every shape this body can show, in the
     * order a phase steps through them — one for a body that never changes, vanilla's eight for a moon. A
     * body whose [CelestialBody.phase] has more steps than shapes would index past the end; `SkyCheck`
     * holds them.
     */
    data class Sprite(
        override val tint: Rgba,
        override val angularSize: Float,
        val shapes: List<Identifier>,
        /**
         * The sprite's colours drawn as others, or null to draw it tinted. A palette replaces the tint in
         * the drawing, so a body may carry a tint other code reads and a palette the sky shows.
         */
        val palette: Palette? = null,
        /** Streamers round it and a shimmer in its glow, or null for none — see [Corona]. */
        val corona: Corona? = null,
    ) : Appearance {
        override val kindKey: String get() = SPRITE

        /** How many distinct shapes this body can show. */
        val cells: Int get() = shapes.size

        /**
         * How much of [angularSize] is the body's disc rather than what is drawn round it. Vanilla's sun and
         * moons are an 8×8 disc in a 32×32 picture, the rest glow or painted sky; a sprite of anybody else's
         * is taken to be all disc.
         */
        val discShare: Float get() = if (shapes.all(::isVanillas)) VANILLAS_DISC_SHARE else 1.0f

        companion object {
            private const val VANILLAS_DISC_SHARE = 8.0f / 32.0f

            private fun isVanillas(shape: Identifier) = shape in SUN_SHAPES || shape in MOON_SHAPES

            val MAP_CODEC: MapCodec<Sprite> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(
                    Rgba.CODEC.optionalFieldOf("tint", Rgba.WHITE).forGetter(Sprite::tint),
                    Codec.FLOAT.fieldOf("size").forGetter(Sprite::angularSize),
                    Identifier.CODEC.listOf().fieldOf("shapes").forGetter(Sprite::shapes),
                    Palette.CODEC.optionalFieldOf("palette").forGetter { java.util.Optional.ofNullable(it.palette) },
                    Corona.CODEC.optionalFieldOf("corona").forGetter { java.util.Optional.ofNullable(it.corona) },
                ).apply(instance) { tint, size, shapes, palette, corona ->
                    Sprite(tint, size, shapes, palette.orElse(null), corona.orElse(null))
                }
            }
        }
    }

    companion object {
        const val SPRITE = "sprite"

        /** Vanilla's own sun sprite: one shape, no phases. */
        val SUN_SHAPES: List<Identifier> = listOf(Identifier.withDefaultNamespace("sun"))

        /**
         * Vanilla's eight moon shapes, **in `MoonPhase` index order**, which is the order a [PhaseCycle]
         * walks and so the order full → waning → new → waxing reads correctly in.
         */
        val MOON_SHAPES: List<Identifier> = MoonPhase.values()
            .sortedBy { it.index() }
            .map { Identifier.withDefaultNamespace("moon/${it.serializedName}") }

        private val KINDS: Map<String, MapCodec<out Appearance>> = mapOf(SPRITE to Sprite.MAP_CODEC)

        /**
         * Dispatched on a `kind` field, so a future variant is additive rather than a format break. An
         * unknown kind falls back to [Sprite]'s codec and fails there on the missing fields, which is a
         * loud failure in the right place rather than a silent one here.
         */
        val CODEC: Codec<Appearance> = Codec.STRING.dispatch(
            "kind",
            Appearance::kindKey,
        ) { key -> KINDS[key] ?: Sprite.MAP_CODEC }
    }
}

/**
 * A body that waxes and wanes. **A phase is a shape, not a brightness** — each shape is its own sprite in
 * the celestials atlas, so this only says *which one*.
 *
 * [steps] must match the sprite's shape count or [stepAt] indexes past the end; `SkyCheck` holds them.
 * Vanilla's eight is a default rather than a rule, nothing outside the renderer being able to observe a
 * phase.
 */
data class PhaseCycle(
    val periodTicks: Int,
    val offsetTicks: Int,
    val steps: Int = VANILLA_PHASES,
    /**
     * Whether the shape follows where the body stands against a sun rather than this cycle: full opposite it, new
     * beside it. The cycle still answers in a sky with no sun.
     */
    val litBySun: Boolean = false,
) {

    /**
     * Which shape this body is showing, in `0..<steps`. Quantised rather than continuous so the stepping
     * is legible instead of an imperceptible crawl, which is why vanilla has eight discrete phases.
     */
    fun stepAt(dayTime: Long): Int {
        if (steps <= 1) return 0
        val revolutions = (dayTime.toDouble() + offsetTicks) / periodTicks
        return (Mth.frac(revolutions) * steps).toInt().coerceIn(0, steps - 1)
    }

    /**
     * The shape a body on [path] shows at [dayTime]: read at its last rising while it is up, so it never changes
     * overhead (Jonah, 2026-08-30, walked), and the hour itself while it is away.
     */
    fun stepShownAt(dayTime: Long, path: CelestialPath, sun: CelestialPath?): Int {
        val heldFrom = if (path.isUpAt(dayTime)) path.lastRoseAt(dayTime) ?: dayTime else dayTime
        return stepAt(heldFrom, path, sun)
    }

    /**
     * How much of the body is lit at [step], `0..1`: whole at the first shape, which is full, and none half a cycle
     * on. A body with one shape is always whole.
     */
    fun fullnessAt(step: Int): Float {
        if (steps < 2) return WHOLE
        val halfACycle = steps / 2.0f
        val fromFull = minOf(step, steps - step)
        return (1.0f - fromFull / halfACycle).coerceIn(0.0f, WHOLE)
    }

    /** The shape of a body on [path] at [dayTime], lit by a sun on [sun] where there is one and [litBySun] says so. */
    fun stepAt(dayTime: Long, path: CelestialPath, sun: CelestialPath?): Int =
        if (litBySun && sun != null) stepLitBy(path, sun, dayTime) else stepAt(dayTime)

    /**
     * The shape in [steps] that the angle between the body and its sun gives, in vanilla's order: full first,
     * waning to new halfway, then waxing back.
     *
     * Waxing is the angle a sun's day later beating the angle a day earlier. A whole day, because a paced sun
     * speeds and slows through its day and over any shorter span a moon falling behind it can seem to gain. A body
     * that never moves against its sun reads as waxing, and one that laps its sun within a day can read either way.
     */
    private fun stepLitBy(path: CelestialPath, sun: CelestialPath, dayTime: Long): Int {
        if (steps <= 1) return 0
        fun elongationAt(time: Long) = CelestialPath.angleBetween(path.directionAt(time), sun.directionAt(time))
        val elongation = elongationAt(dayTime)
        val aDay = if (sun.isStill) WAXING_LOOK_AHEAD else sun.periodTicks.toLong()
        val isWaxing = elongationAt(dayTime + aDay) >= elongationAt(dayTime - aDay)
        val newStep = steps / 2
        val stepsFromNew = Math.round(elongation / Math.PI * newStep).toInt()
        return if (isWaxing) (newStep + stepsFromNew) % steps else newStep - stepsFromNew
    }

    companion object {
        /** Not `const`: `MoonPhase.COUNT` is `values().length`, so it is not a compile-time constant. */
        val VANILLA_PHASES = MoonPhase.COUNT

        /** How far either side waxing is read beside a sun that never moves, where nothing paces it. */
        private const val WAXING_LOOK_AHEAD = 200L

        private const val WHOLE = 1.0f

        val CODEC: Codec<PhaseCycle> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("period").forGetter(PhaseCycle::periodTicks),
                Codec.INT.optionalFieldOf("offset", 0).forGetter(PhaseCycle::offsetTicks),
                Codec.INT.optionalFieldOf("steps", VANILLA_PHASES).forGetter(PhaseCycle::steps),
                Codec.BOOL.optionalFieldOf("lit_by_sun", false).forGetter(PhaseCycle::litBySun),
            ).apply(instance, ::PhaseCycle)
        }
    }
}

/**
 * How a body's sprite meets the sky behind it.
 *
 * Every texture in the celestials atlas is fully opaque, about two thirds of it near-black, which vanilla never
 * shows because it adds both bodies. Vanilla's moon shapes still cover cleanly: the canvas crops them to their
 * disc and adds the glow round it. A covering sprite of anybody else's is drawn whole, so it needs an alpha
 * channel of its own. `CelestialTextureCheck` holds the fact about the atlas.
 */
enum class Blending(private val key: String) {
    /** Added to the sky, so the sprite reads as a light source and its dark parts are not there. Vanilla's way. */
    ADDS("adds"),

    /** Painted over the sky, so the body hides whatever is behind it: one moon eclipsing another, or a sun. */
    COVERS("covers"),
    ;

    companion object {
        val CODEC: com.mojang.serialization.Codec<Blending> = com.mojang.serialization.Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: ADDS },
            { it.key },
        )
    }
}
