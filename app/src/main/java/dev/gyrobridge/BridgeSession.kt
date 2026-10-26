package dev.gyrobridge

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStreamWriter
import java.util.UUID

/** Owns one authenticated helper connection. Closing it always releases native input. */
class BridgeSession : Closeable {
    @Volatile private var socket: LocalSocket? = null
    private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    fun connect(context: Context, onLine: (String) -> Unit) {
        if (closed) return
        try {
            val token = UUID.randomUUID().toString().replace("-", "")
            val name = "gyrobridge.$token"
            launchHelper(context, name, token)
            val client = awaitConnection(name)
            if (client == null) {
                if (closed) return
                val detail = runCatching { context.filesDir.resolve("bridge.log").readText().takeLast(1600) }
                    .getOrDefault("Check firmware access.")
                error("Stock helper did not start. $detail")
            }
            synchronized(this) {
                if (closed) return
                writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
                send("AUTH $token")
            }
            client.inputStream.bufferedReader().use { input ->
                while (!closed) onLine(input.readLine() ?: break)
            }
            if (!closed) error("Input bridge disconnected; physical controller restored.")
        } finally {
            close()
        }
    }

    private fun launchHelper(context: Context, name: String, token: String) {
        val info = context.applicationInfo
        val logFile = context.filesDir.resolve("bridge.log")
        logFile.delete()
        logFile.writeText("") // App ownership keeps launch errors readable.
        val q = StockShell::quote
        val launch = context.filesDir.resolve("launch.sh")
        // PServer returns one output line. The detached child must ignore HUP and
        // close the service's pipes before the launch transaction completes.
        launch.writeText("""
            #!/system/bin/sh
            (
            trap '' HUP
            export CLASSPATH=${q(info.sourceDir)}
            exec /system/bin/app_process / dev.gyrobridge.BridgeMain ${q(info.nativeLibraryDir)} ${q(name)} ${Process.myUid()} ${q(token)}
            ) > ${q(logFile.absolutePath)} 2>&1 < /dev/null &
            echo GYROBRIDGE_LAUNCHED
        """.trimIndent() + "\n")
        val reply = StockShell.execute("/system/bin/sh ${q(launch.absolutePath)}")
        check(reply.contains("GYROBRIDGE_LAUNCHED")) { "AYN helper launch failed: $reply" }
    }

    private fun awaitConnection(name: String): LocalSocket? {
        repeat(50) {
            val candidate = LocalSocket()
            synchronized(this) {
                if (closed) {
                    candidate.close()
                    return null
                }
                // Publish even an in-progress connection so close() can interrupt it.
                socket = candidate
            }
            try {
                candidate.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                return candidate
            } catch (e: Exception) {
                candidate.close()
                synchronized(this) { if (socket === candidate) socket = null }
                if (closed) return null
                Thread.sleep(100)
            }
        }
        return null
    }

    @Synchronized fun send(line: String) {
        writer?.apply {
            write(line)
            newLine()
            flush()
        }
    }

    override fun close() {
        closed = true
        // Interrupt blocked I/O before acquiring the writer's lock.
        runCatching { socket?.close() }
        synchronized(this) {
            writer = null
            socket = null
        }
    }
}
