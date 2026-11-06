package co.voik.ephemeris.client

import co.voik.ephemeris.sky.Eclipse
import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

/**
 * The glare of each sun that dazzles ([co.voik.ephemeris.sky.CelestialBody.glare]): the view washed with its light as
 * the eye turns toward it, so long as nothing stands between and no moon covers it. Where each stands and whether it
 * is seen are asked once a tick ([tick]); how far the eye faces it, every frame ([draw]).
 */
object SunGlare {

    val ID: Identifier = Identifier.fromNamespaceAndPath("ephemeris", "sun_glare")

    /** One sun's glare as the eye last saw it, kept by the sun's index among the bodies. */
    private class Glare(var strength: Float, var toward: Vector3f)

    private data class Sighting(val strength: Float, val toward: Vector3f)

    private val glares = mutableMapOf<Int, Glare>()

    fun tick(minecraft: Minecraft) {
        val seen = sightings(minecraft)
        for (index in glares.keys + seen.keys) {
            val glare = glares.getOrPut(index) { Glare(0.0f, Vector3f(0.0f, 1.0f, 0.0f)) }
            val sighting = seen[index]
            if (sighting != null) glare.toward = sighting.toward
            glare.strength = Mth.approach(glare.strength, sighting?.strength ?: 0.0f, EASED_PER_TICK)
        }
        glares.entries.removeIf { (index, glare) -> glare.strength <= 0.0f && index !in seen }
    }

    private fun sightings(minecraft: Minecraft): Map<Int, Sighting> {
        val level = minecraft.level ?: return emptyMap()
        val player = minecraft.player ?: return emptyMap()
        val look = LevelLooks.of(level.dimension()) ?: return emptyMap()
        val clock = level.defaultClockTime
        val clearSky = 1.0f - level.getRainLevel(1.0f)
        val eye = player.eyePosition
        fun isBlockedToward(toward: Vector3f): Boolean {
            val sight = eye.add(Vec3(toward).scale(SIGHT_BLOCKS))
            return level.clip(ClipContext(eye, sight, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).type != HitResult.Type.MISS
        }
        return look.readAt(clock).suns.filter { it.body.glare > 0.0f }.associate { sun ->
            val toward = sun.body.path.directionAt(clock)
            val uncovered = 1.0f - (Eclipse.of(look.sky, sun.index, clock)?.coverage ?: 0.0f)
            val strength = if (isBlockedToward(toward)) 0.0f
                else sun.body.glare * Eclipse.lookAt(sun.altitudeDegrees) * uncovered * clearSky
            sun.index to Sighting(strength, toward)
        }
    }

    fun draw(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        if (glares.isEmpty()) return
        val player = Minecraft.getInstance().player ?: return
        val looking = player.getViewVector(delta.getGameTimeDeltaPartialTick(false)).toVector3f()
        for (glare in glares.values) {
            val facing = looking.dot(glare.toward)
            val turnedToward = Mth.clamp(Mth.inverseLerp(facing, FACING_FROM, 1.0f), 0.0f, 1.0f)
            val wash = glare.strength * turnedToward * turnedToward * BRIGHTEST
            if (wash <= 0.0f) continue
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.colorFromFloat(wash, GLARE_RED, GLARE_GREEN, GLARE_BLUE))
        }
    }

    /** How far toward the sun its glare is looked for, in blocks: past this a hill is too far off to shade the eye. */
    private const val SIGHT_BLOCKS = 256.0

    /** How quickly the glare comes and goes as the sun is hidden or found, per tick. */
    private const val EASED_PER_TICK = 0.15f

    /** How far the eye faces the sun before its glare begins: the cosine of about forty degrees off it. */
    private const val FACING_FROM = 0.77f

    /** How much of the view a sun at full glare washes out, looked straight at. */
    private const val BRIGHTEST = 0.55f

    /** The glare's light: a warm white. */
    private const val GLARE_RED = 1.0f
    private const val GLARE_GREEN = 0.96f
    private const val GLARE_BLUE = 0.86f
}
