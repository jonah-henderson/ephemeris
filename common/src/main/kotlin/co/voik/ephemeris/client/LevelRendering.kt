package co.voik.ephemeris.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.level.MoonPhase
import net.minecraft.world.phys.Vec3

/**
 * How a level looks, for levels that should not look like the overworld.
 *
 * **This is the escape hatch, and everything else is built on it.** The vocabulary tier — suns, moons,
 * starfields, cloud decks — is a renderer registered here like any other, so nothing it can do is closed to
 * a caller who wants to draw their own, and the convenient road is the same road.
 *
 * **It exists because 26.1 closed every other one.** `DimensionSpecialEffects` is gone, Fabric API dropped
 * `DimensionRenderingRegistry`, and a dimension type still cannot carry this to the client — so a mod that
 * wants a sky of its own has to Mixin `SkyRenderer` itself. Every such mod writes the same two Mixins, and
 * two mods that do fight over the same seam.
 *
 * **A renderer decides for itself whether it applies**, rather than being registered against a level key.
 * That is deliberate: the interesting cases are dynamic — a level made at runtime, a look that changes when
 * its description is rewritten — and a registration keyed by dimension cannot express either. Returning
 * `false` means "not mine", and the next renderer is asked; if none claims it, vanilla draws its own.
 *
 * **Registration order is the order asked**, and the first claim wins.
 */
object LevelRendering {

    private val skies = mutableListOf<LevelSkyRenderer>()
    private val clouds = mutableListOf<LevelCloudRenderer>()
    private val environments = mutableListOf<LevelEnvironment>()

    /** Offer to draw suns, moons and stars. */
    fun sky(renderer: LevelSkyRenderer) {
        skies += renderer
    }

    /** Offer to draw the overcast. */
    fun clouds(renderer: LevelCloudRenderer) {
        clouds += renderer
    }

    /**
     * Offer to bend a level's air — its fog, sky colour, light tint and how far you can see.
     *
     * Unlike the two above this one **does not claim**: every layer offered is applied, in order, over
     * whatever the last one left. Attributes compose by their nature and a first-claim rule would make two
     * mods that both tint the fog silently exclusive.
     */
    fun environment(layers: LevelEnvironment) {
        environments += layers
    }

    // The three below are what the Mixins call. Public because a Java Mixin cannot see a Kotlin
    // `internal` — the name is mangled — and not because a consumer has any business calling them.

    /** Asked by the sky Mixin. Registering a renderer is the way in; this is the way out. */
    fun drawSky(moment: SkyMoment): Boolean = skies.any { it.draw(moment) }

    /** Asked by the cloud Mixin. */
    fun drawClouds(moment: CloudMoment): Boolean = clouds.any { it.draw(moment) }

    /** Asked by the client-level Mixin, once, as the level builds its attribute system. */
    fun paintEnvironment(
        level: ClientLevel,
        layers: EnvironmentAttributeSystem.Builder,
    ): EnvironmentAttributeSystem.Builder = environments.fold(layers) { built, each -> each.paint(level, built) }
}

/** Everything vanilla knows at the instant it is about to draw a sky. */
class SkyMoment(
    val level: ClientLevel,
    /** Vanilla's own angles, in radians, so a body on its path needs nothing reconstructed. */
    val sunAngle: Float,
    val moonAngle: Float,
    val starAngle: Float,
    val moonPhase: MoonPhase,
    /** How much of the sky the weather is hiding, `0..1`. */
    val rainBrightness: Float,
    /** How visible stars are at this hour, before anything of yours dims them further. */
    val starBrightness: Float,
)

/** Draws a level's suns, moons and stars. */
fun interface LevelSkyRenderer {
    /** **True** if this drew the sky; **false** to pass, leaving it to the next renderer or to vanilla. */
    fun draw(moment: SkyMoment): Boolean
}

/** Everything vanilla knows at the instant it is about to draw clouds. */
class CloudMoment(
    val level: ClientLevel,
    val colour: Int,
    val status: net.minecraft.client.CloudStatus,
    val bottomY: Float,
    val range: Int,
    val cameraPosition: Vec3,
    /** The game time with the partial tick already added, so movement is smooth without doing that again. */
    val time: Float,
)

/** Draws a level's overcast. */
fun interface LevelCloudRenderer {
    /** **True** if this drew the clouds; **false** to pass. */
    fun draw(moment: CloudMoment): Boolean
}

/**
 * Bends a level's air.
 *
 * The builder is vanilla's own, so anything it accepts works here — a constant layer for the whole level, a
 * layer that applies only in one biome, or a **positional** one asked per position, which is what a gradient
 * around something in the world needs.
 */
fun interface LevelEnvironment {
    fun paint(
        level: ClientLevel,
        layers: EnvironmentAttributeSystem.Builder,
    ): EnvironmentAttributeSystem.Builder
}
