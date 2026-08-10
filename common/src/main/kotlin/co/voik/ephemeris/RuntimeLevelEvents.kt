package co.voik.ephemeris

import net.minecraft.server.level.ServerLevel

/**
 * The attachment points a runtime level offers — **the part that exists because using someone else's library
 * made us wish for them.**
 *
 * Fantasy tells you a level exists by returning it, and that is all: anything wanting to know *later* — a
 * feature that must attach to every such level, a system that must be told before one goes away, a thing
 * that has to run once per level per boot — has to be wired by hand at every call site and kept in step with
 * every other one. That works until there are three of them.
 *
 * So a listener registered here is called for every runtime level however it came to exist, including the
 * ones re-opened on boot, and a consumer's own bookkeeping stops depending on it having remembered to hook
 * each caller.
 *
 * **Listeners are called on the server thread**, in registration order, and a throwing listener must not
 * take the level down with it — a level half-added because somebody's listener failed is worse than a
 * listener that did not run.
 */
object RuntimeLevelEvents {

    private val onOpened = mutableListOf<(ServerLevel) -> Unit>()
    private val onClosing = mutableListOf<(ServerLevel) -> Unit>()

    /** Called after the level is in the map and tickable — so a listener may touch it freely. */
    fun whenOpened(listener: (ServerLevel) -> Unit) {
        onOpened += listener
    }

    /** Called while the level is still usable, so a listener can save or move what it owns. */
    fun whenClosing(listener: (ServerLevel) -> Unit) {
        onClosing += listener
    }

    internal fun opened(level: ServerLevel) = each(onOpened, level, "opened")

    internal fun closing(level: ServerLevel) = each(onClosing, level, "closing")

    /**
     * **One listener's failure is its own.** These run inside level creation, and a listener that throws
     * would otherwise leave a level in the map that its caller believes does not exist.
     */
    private fun each(listeners: List<(ServerLevel) -> Unit>, level: ServerLevel, what: String) {
        for (listener in listeners) {
            runCatching { listener(level) }.onFailure {
                RuntimeLevelLog.warn("A $what listener threw for ${level.dimension().identifier()}", it)
            }
        }
    }
}
