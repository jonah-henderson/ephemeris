package co.voik.ephemeris.client

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel

/**
 * Which level is being drawn, and where it is being drawn to — for anyone rendering a level that is not
 * the player's.
 *
 * **Nothing in the client renderer says which level a frame is for.** `SkyRenderer.renderSunMoonAndStars`
 * and `CloudRenderer.render` take angles and colours and no level at all, and `LevelRenderer.renderLevel`
 * reaches `Minecraft.getMainRenderTarget()` for itself. So code hanging off those seams has historically
 * asked `Minecraft.getInstance().level` and got the right answer by there only ever being one. A second
 * level breaks every such caller at once — a portal, a mirror, a camera feed, a preview panel — and each
 * breaks *silently*, drawing the player's sky into somebody else's window.
 *
 * This is the missing piece of context, declared once by whoever is driving the off-screen render:
 *
 * ```
 * OffscreenLevelRender.drawing(previewLevel, onto = panelTarget, from = orbitCamera) {
 *     previewRenderer.renderLevel(…)
 * }
 * ```
 *
 * **Everything registered on [LevelRendering] is handed its level and its target directly** and never needs
 * this — a `SkyMoment` carries both. This exists for the three places that cannot be reached that way: the
 * Mixins, which must decide *which* level a moment is even for; singleton canvases like
 * [Blaze3dSkyCanvas], shared across every level and so unable to hold a target of their own; and anything
 * asking where the eye is, since `gameRenderer.mainCamera` is the player's wherever it is read from.
 *
 * **Render thread only, and deliberately not thread-local.** A frame is drawn on one thread and the scope
 * exists for the length of one render call. Work that happens off the render thread — section compilation
 * and the block colours it asks for — cannot use this and does not: see [GroundTints], which documents what
 * it gets wrong instead.
 */
object OffscreenLevelRender {

    private var drawnLevel: ClientLevel? = null
    private var drawnTarget: RenderTarget? = null
    private var drawnCloudTarget: RenderTarget? = null
    private var drawnCamera: Camera? = null

    /**
     * Runs [block] with the renderer attributed to [level], drawing onto [onto], seen [from] a camera.
     *
     * [clouds] is the separate target vanilla gives the cloud pass when the transparency chain is on; pass
     * null and clouds draw onto [onto] like everything else, which is what an off-screen render usually
     * wants. [from] is the camera the frame is being seen through — an orbit, a portal's other side — and
     * matters to anything that asks how high the eye is or samples an environment attribute where it
     * stands.
     *
     * Nests safely: the previous scope is restored afterwards, including the outermost case of no scope at
     * all, so a failure inside [block] cannot leave the renderer pointed at a level that has gone.
     */
    fun <T> drawing(
        level: ClientLevel,
        onto: RenderTarget,
        from: Camera,
        clouds: RenderTarget? = null,
        block: () -> T,
    ): T {
        val outerLevel = drawnLevel
        val outerTarget = drawnTarget
        val outerCloudTarget = drawnCloudTarget
        val outerCamera = drawnCamera
        drawnLevel = level
        drawnTarget = onto
        drawnCloudTarget = clouds
        drawnCamera = from
        try {
            return block()
        } finally {
            drawnLevel = outerLevel
            drawnTarget = outerTarget
            drawnCloudTarget = outerCloudTarget
            drawnCamera = outerCamera
        }
    }

    /**
     * The level being drawn: the one [drawing] named, or the player's own outside any such scope.
     *
     * Null only where there is no level at all, which is the main menu.
     */
    fun levelBeingDrawn(): ClientLevel? = drawnLevel ?: Minecraft.getInstance().level

    /** Where this frame is going: the target [drawing] named, or the window's own outside any scope. */
    fun targetBeingDrawnOnto(): RenderTarget =
        drawnTarget ?: Minecraft.getInstance().gameRenderer.mainRenderTarget()

    /**
     * Where the cloud pass is going.
     *
     * Vanilla gives clouds a target of their own only when the transparency chain is on, and falls back to
     * the main one otherwise — so this answers the same way, and an off-screen render that named no cloud
     * target simply draws its clouds where everything else goes.
     */
    fun cloudTargetBeingDrawnOnto(): RenderTarget {
        drawnTarget?.let { return drawnCloudTarget ?: it }
        // 26.3 gave clouds order-independent transparency and took their own colour target away with it
        // (`LevelTargetBundle` keeps only `oitCloudDepth`), so there is one target again.
        return Minecraft.getInstance().gameRenderer.mainRenderTarget()
    }

    /**
     * The camera this frame is seen through: the one [drawing] named, or the game's own outside any scope.
     *
     * Wanted by anything asking where the eye is or what the air is like where it stands — both of which
     * answer for the *player* if taken from `Minecraft` while another level is being drawn.
     */
    fun cameraBeingDrawnFrom(): Camera = drawnCamera ?: Minecraft.getInstance().gameRenderer.mainCamera()

    /** Whether a level other than the player's is being drawn — for a caller that wants to skip work. */
    fun isDrawingElsewhere(): Boolean = drawnLevel != null
}
