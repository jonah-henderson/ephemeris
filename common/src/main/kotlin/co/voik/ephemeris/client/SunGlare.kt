package co.voik.ephemeris.client

import co.voik.ephemeris.sky.Eclipse
import co.voik.ephemeris.sky.LevelDaylight
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
 * The glare of a sun that dazzles ([co.voik.ephemeris.sky.CelestialBody.glare]): the view washed with its light as
 * the eye turns toward it, so long as nothing stands between. Where it stands and whether it is seen are asked once a
 * tick ([tick]); how far the eye faces it, every frame ([draw]).
 */
object SunGlare {

    val ID: Identifier = Identifier.fromNamespaceAndPath("ephemeris", "sun_glare")

    private var strength = 0.0f
    private var toward = Vector3f(0.0f, 1.0f, 0.0f)

    fun tick(minecraft: Minecraft) {
        strength = Mth.approach(strength, targetStrength(minecraft), EASED_PER_TICK)
    }

    private fun targetStrength(minecraft: Minecraft): Float {
        val level = minecraft.level ?: return 0.0f
        val player = minecraft.player ?: return 0.0f
        val look = LevelLooks.of(level.dimension()) ?: return 0.0f
        val clock = level.defaultClockTime
        val sun = LevelDaylight.decidingSunOf(look, clock) ?: return 0.0f
        if (sun.body.glare <= 0.0f) return 0.0f
        toward = sun.body.path.directionAt(clock)
        val eye = player.eyePosition
        val sight = eye.add(Vec3(toward).scale(SIGHT_BLOCKS))
        val blocked = level.clip(ClipContext(eye, sight, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).type != HitResult.Type.MISS
        if (blocked) return 0.0f
        val clearSky = 1.0f - level.getRainLevel(1.0f)
        return sun.body.glare * Eclipse.lookAt(sun.altitudeDegrees) * clearSky
    }

    fun draw(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        if (strength <= 0.0f) return
        val player = Minecraft.getInstance().player ?: return
        val facing = player.getViewVector(delta.getGameTimeDeltaPartialTick(false)).toVector3f().dot(toward)
        val turnedToward = Mth.clamp(Mth.inverseLerp(facing, FACING_FROM, 1.0f), 0.0f, 1.0f)
        val wash = strength * turnedToward * turnedToward * BRIGHTEST
        if (wash <= 0.0f) return
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ARGB.colorFromFloat(wash, GLARE_RED, GLARE_GREEN, GLARE_BLUE))
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
