package dev.gyrobridge

import android.os.IBinder
import android.os.Parcel
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Uses only the command service shipped by AYN. Never invokes su. */
object StockShell {
    @Synchronized fun execute(command: String): String {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;")
        val type = Class.forName("android.os.ServiceManager")
        val binder = type.getDeclaredMethod("getService", String::class.java)
            .invoke(null, "PServerBinder") as? IBinder
            ?: error("AYN's stock command service is unavailable on this firmware.")
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeStringArray(arrayOf(command, "1"))
            check(binder.transact(0, request, reply, 0)) { "AYN rejected the command transaction." }
            return reply.createByteArray()?.toString(Charsets.UTF_8)?.trim().orEmpty()
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
