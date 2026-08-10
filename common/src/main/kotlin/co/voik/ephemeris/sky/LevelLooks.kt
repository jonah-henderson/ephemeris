package co.voik.ephemeris.sky

import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * What each level looks like, as this client has been told.
 *
 * **This is the middle rung of three, and the one that makes "bring your own transport" possible.** Below it
 * is `LevelRendering`, where a caller draws whatever they like and this vocabulary is not involved at all.
 * Above it is the library's own sync, which fills this in for you. Here, a caller keeps the vocabulary —
 * suns, moons, starfields, cloud decks — and supplies the appearance themselves, from their own packet,
 * their own config, or a rule of their own.
 *
 * **The client cannot derive any of this**, which is the whole reason the rung exists. `DimensionType`'s
 * stream codec writes registry ids only, so a level made at runtime cannot carry its appearance in the join
 * packet; registries freeze at startup and runtime levels do not exist yet. Plain data on a channel can, and
 * something has to put it here.
 *
 * **In `common` despite being client-only state**, because both loaders' receivers write here and two caches
 * could disagree.
 */
object LevelLooks {

    private val looks = mutableMapOf<ResourceKey<Level>, LevelLook>()

    /**
     * How [dimension] looks, or **null** where nobody has said.
     *
     * Null means *unanswered*, not *ordinary*: the first frames of a connection genuinely have no answer,
     * and a renderer that conflated the two would make a dropped packet look like a decision.
     */
    fun of(dimension: ResourceKey<Level>): LevelLook? = looks[dimension]

    /**
     * Record what a level looks like, **replacing** any earlier answer for it.
     *
     * Replacing rather than merging is deliberate: a level whose description is rewritten gets a new
     * appearance, and a store keeping the first answer would show the old one until the client restarted.
     * It is also why renderers read this every frame rather than registering per dimension — Fabric's own
     * registry is `putIfAbsent` with no removal.
     */
    fun remember(dimension: ResourceKey<Level>, look: LevelLook) {
        looks[dimension] = look
    }

    /** Everything a payload said, which is what a loader's receiver hands over and all it has to do. */
    fun remember(payload: LevelLookPayload) {
        for (entry in payload.looks) remember(entry.dimension, entry.look)
    }

    /**
     * Forgotten on disconnect, because these keys mean nothing on the next server and an id can be reused.
     * Keeping them would let one world's sky appear in another's.
     */
    fun forgetAll() = looks.clear()
}

/**
 * Everything about how one level looks — its sky, and the air the eye sees through.
 *
 * Both halves ride together because they are the same fact: what a client should show for that level. Split
 * in two they would be two messages, two codecs, and two chances to disagree about one place.
 */
data class LevelLook(
    val sky: SkySpec,
    /** The air over the whole level. */
    val air: Look = Look.NOTHING,
    /** And any corner of it painted differently — keyed by biome. */
    val corners: Map<Identifier, Look> = emptyMap(),
) {
    /** Whether this says anything at all, so a renderer can decline cheaply. */
    val saysNothing: Boolean
        get() = sky.isOrdinary && air.saysNothing && corners.isEmpty() && sky.decks.isEmpty()
}
