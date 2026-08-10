package co.voik.ephemeris.debug

import co.voik.ephemeris.RuntimeLevelPlatform
import co.voik.ephemeris.sky.LevelAppearance
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.LevelLookPayload
import net.minecraft.server.level.ServerLevel

/**
 * Show a level an appearance the server does not believe in — **an instrument for tuning one, and not part
 * of the ordinary API**.
 *
 * `LevelAppearance` offers one verb, [LevelAppearance.give], on purpose: sending is what changing means, so
 * a client cannot end up disagreeing with the server about a place. Showing without giving puts that
 * footgun back — two players in one level seeing two skies, and no record anywhere of why. It lives in a
 * `debug` package for that reason, and a mod shipping a call to it has almost certainly reached for the
 * wrong one.
 *
 * What it *is* good for is watching a palette move while you edit it, which wants no round trip through
 * whatever decides appearances and no state to remember to undo.
 *
 * **Deliberately stateless, so it is self-cancelling.** Nothing is recorded, so anything that re-tells this
 * player — leaving and coming back, a re-[LevelAppearance.give] — restores what the server actually says.
 * There is no override to persist, to restore on restart, or to forget you left on.
 */
object LevelLookPreview {

    /** Shows [look] to everyone standing in [level], until something tells them otherwise. */
    fun show(level: ServerLevel, look: LevelLook) {
        val payload = LevelLookPayload(listOf(LevelLookPayload.Entry(level.dimension(), look)))
        for (player in level.players()) RuntimeLevelPlatform.of().sendToPlayer(player, payload)
    }
}
