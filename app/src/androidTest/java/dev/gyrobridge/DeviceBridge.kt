package dev.gyrobridge

import android.content.Context
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Test fixture owns its connection, reader and optional heartbeat. */
class DeviceBridge(context: Context, heartbeat: Boolean = true) : Closeable {
    val lines = CopyOnWriteArrayList<String>()
    private val session = BridgeSession()
    private val ready = CountDownLatch(1)
    private val error = AtomicReference<Throwable?>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val reader = thread(name = "test-bridge") {
        try {
            session.connect(context) { line ->
                lines += line
                if (line.startsWith("READY ")) ready.countDown()
            }
        } catch (e: Throwable) {
            error.set(e)
            ready.countDown()
        }
    }

    init {
        if (heartbeat) timer.scheduleWithFixedDelay({ runCatching { send("PING") } }, 200, 200, TimeUnit.MILLISECONDS)
    }

    fun awaitReady() {
        check(ready.await(8, TimeUnit.SECONDS)) { "Stock helper did not respond" }
        error.get()?.let { throw it }
        check(lines.any { it.startsWith("READY ") }) { "Missing READY: $lines" }
    }

    fun send(command: String) = session.send(command)
    fun checkHealthy() { error.get()?.let { throw it } }

    override fun close() {
        timer.shutdownNow()
        runCatching { send("QUIT") }
        session.close()
        reader.join(1500)
    }
}
