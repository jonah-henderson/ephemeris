package co.voik.ephemeris.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * A `ClientLevel` whose air can be built again — `ClientLevelMixin` implements it.
 *
 * A level's air is built once, in its constructor, from whatever the client was told by then. A player who
 * joins straight into a level is in it before its look arrives, so the look never reached its air: a
 * violet sky came back blue after a restart.
 */
interface RepaintableAir {
    /** Builds the level's attributes again, through the same layers its constructor used. */
    fun repaintEphemerisAir()
}

/** What changes on the client when a level's look does — what `LevelLooks.whenTold` is set to. */
object LookArrivals {
    fun told(dimension: ResourceKey<Level>) {
        GroundTints.forget(dimension)
        val level = Minecraft.getInstance().level ?: return
        if (level.dimension() == dimension) (level as? RepaintableAir)?.repaintEphemerisAir()
    }
}
