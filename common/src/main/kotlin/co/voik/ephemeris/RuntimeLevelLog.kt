package co.voik.ephemeris

import org.slf4j.LoggerFactory

/**
 * The library's own logger.
 *
 * Its own rather than the consuming mod's, because **this package may not know what mod it is inside** —
 * that is the whole of what makes it extractable. Anything here that reaches into `co.voik.agesandtheart`
 * is a thing that has to be undone before it can be given away.
 */
internal object RuntimeLevelLog {
    private val log = LoggerFactory.getLogger("ephemeris")

    fun warn(message: String, cause: Throwable? = null) {
        if (cause == null) log.warn(message) else log.warn(message, cause)
    }
}
