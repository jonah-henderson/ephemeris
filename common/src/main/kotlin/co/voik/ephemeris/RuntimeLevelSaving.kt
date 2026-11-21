package co.voik.ephemeris

import net.minecraft.TracingExecutor
import net.minecraft.util.Util
import net.minecraft.world.level.storage.SavedDataStorage
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a runtime level's saved data is written: a few threads of its own, rather than vanilla's I/O pool.
 *
 * Every level writes its dirty saved data on every save, and a ticking level's weather, clock, raids and trader
 * timer are dirty every time. Vanilla's pool starts a new thread for each write that finds none idle, and starts it
 * on the server thread — a few for vanilla's three levels, but over a thousand for a couple of hundred runtime
 * levels, which held the server for seconds. Here a runtime level's writes queue on [WRITERS] instead, and the
 * server thread only hands them over.
 *
 * **Only levels this library opened.** Every other level keeps vanilla's pool, so a backlog of runtime levels' writes
 * never delays the overworld's.
 */
object RuntimeLevelSaving {

    /** The storages of the levels [RuntimeLevels] opened; weak, so a closed level's storage is not kept. */
    private val managed: MutableSet<SavedDataStorage> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    internal fun manage(storage: SavedDataStorage) {
        managed += storage
    }

    /**
     * Where [storage]'s writes go.
     *
     * Asked by the Mixin. Public because a Java Mixin cannot see a Kotlin `internal`, whose name is mangled.
     */
    @JvmStatic
    fun writerFor(storage: SavedDataStorage): TracingExecutor = if (storage in managed) WRITERS else Util.ioPool()

    private const val WRITER_THREADS = 2

    /** How long an idle writer waits for more before it ends, as vanilla's I/O pool's do. */
    private const val IDLE_SECONDS = 60L

    private val WRITERS: TracingExecutor = run {
        val counted = AtomicInteger()
        val named = ThreadFactory { work -> Thread(work, "Ephemeris-Level-Saving-${counted.incrementAndGet()}") }
        val pool = ThreadPoolExecutor(
            WRITER_THREADS, WRITER_THREADS, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(), named,
        )
        pool.allowCoreThreadTimeOut(true)
        TracingExecutor(pool)
    }
}
