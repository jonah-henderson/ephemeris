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
) {
    companion object {
        val CODEC: Codec<CelestialBody> = RecordCodecBuilder.create { instance ->
            instance.group(
                CelestialPath.CODEC.fieldOf("orbit").forGetter(CelestialBody::path),
                Appearance.CODEC.fieldOf("appearance").forGetter(CelestialBody::appearance),
                PhaseCycle.CODEC.optionalFieldOf("phase").forGetter { body -> java.util.Optional.ofNullable(body.phase) },
                Blending.CODEC.optionalFieldOf("blending", Blending.ADDS).forGetter(CelestialBody::blending),
            ).apply(instance) { path, appearance, phase, blending ->
                CelestialBody(path, appearance, phase.orElse(null), blending)
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

        companion object {
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
data class PhaseCycle(val periodTicks: Int, val offsetTicks: Int, val steps: Int = VANILLA_PHASES) {

    /**
     * Which shape this body is showing, in `0..<steps`. Quantised rather than continuous so the stepping
     * is legible instead of an imperceptible crawl, which is why vanilla has eight discrete phases.
     */
    fun stepAt(dayTime: Long): Int {
        if (steps <= 1) return 0
        val revolutions = (dayTime.toDouble() + offsetTicks) / periodTicks
        return (Mth.frac(revolutions) * steps).toInt().coerceIn(0, steps - 1)
    }

    companion object {
        /** Not `const`: `MoonPhase.COUNT` is `values().length`, so it is not a compile-time constant. */
        val VANILLA_PHASES = MoonPhase.COUNT

        val CODEC: Codec<PhaseCycle> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("period").forGetter(PhaseCycle::periodTicks),
                Codec.INT.optionalFieldOf("offset", 0).forGetter(PhaseCycle::offsetTicks),
                Codec.INT.optionalFieldOf("steps", VANILLA_PHASES).forGetter(PhaseCycle::steps),
            ).apply(instance, ::PhaseCycle)
        }
    }
}

/**
 * How a body's sprite meets the sky behind it.
 *
 * **[COVERS] needs a sprite with real transparency, and vanilla's have none.** Every texture in the
 * celestials atlas — the sun and all eight moon shapes — is indexed colour with no `tRNS` chunk, so every
 * pixel is fully opaque, and about two thirds of each is near-black. Vanilla never notices because it draws
 * both bodies additively, where black contributes nothing. Draw one of them covering and that black square
 * is painted over the sky: a moon in daylight with an enormous dark blob around it, which is exactly what it
 * looked like. `CelestialTextureCheck` holds the fact the default rests on.
 */
enum class Blending(private val key: String) {
    /**
     * Added to the sky, so the sprite reads as a light source and its dark parts are simply not there.
     *
     * **Vanilla's way for both its sun and its moon**, and the only one that works with vanilla's own
     * sprites. The default for that reason.
     */
    ADDS("adds"),

    /**
     * Painted over the sky, so the body hides whatever is behind it — one moon eclipsing another, or a sun.
     *
     * Only worth asking for with a sprite that has an alpha channel. With one of vanilla's, this paints the
     * texture's opaque black background across the sky.
     */
    COVERS("covers"),
    ;

    companion object {
        val CODEC: com.mojang.serialization.Codec<Blending> = com.mojang.serialization.Codec.STRING.xmap(
            { key -> entries.firstOrNull { it.key == key } ?: ADDS },
            { it.key },
        )
    }
}
