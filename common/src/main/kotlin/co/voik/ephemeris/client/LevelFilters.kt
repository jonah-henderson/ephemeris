package co.voik.ephemeris.client

import co.voik.ephemeris.sky.LevelLooks
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.resources.Identifier

/**
 * Keeps the local player's post effects carrying the [co.voik.ephemeris.sky.LevelLook.filters] of the level they
 * stand in, and only those of ours — asked every client tick.
 *
 * Vanilla draws every effect on `LocalPlayer.getActivePostEffects` over the level and under the GUI, and the
 * server replaces that list when it sends its own, so whatever it drops is put back on the next tick.
 */
object LevelFilters {

    private var player: LocalPlayer? = null
    private var added: List<Identifier> = emptyList()

    fun tick(minecraft: Minecraft) {
        val current = minecraft.player
        if (current !== player) {
            player = current
            added = emptyList()
        }
        if (current == null) return
        val wanted = LevelLooks.of(current.level().dimension())?.filters.orEmpty()
        val active = current.activePostEffects
        active.removeAll(added - wanted.toSet())
        for (filter in wanted) if (filter !in active) active += filter
        added = wanted
    }
}
