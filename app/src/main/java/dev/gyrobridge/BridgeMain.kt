package dev.gyrobridge

import android.os.Looper
import android.view.InputDevice

/** Entry point for the isolated process launched by AYN's stock service. */
object BridgeMain {
    @Suppress("DEPRECATION")
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 4)
            Looper.prepareMainLooper()
            System.load(args[0] + "/libgyrobridge.so")
            NativeBridge.run(args[1], args[2].toInt(), args[3])
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @JvmStatic fun findControllerId(vendor: Int, product: Int, name: String): Int =
        InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { it.vendorId == vendor && it.productId == product && it.name == name }?.id
            ?: error("AYN controller is missing from Android InputManager")

    @JvmStatic fun awaitDevice(name: String): Boolean {
        repeat(100) {
            if (InputDevice.getDeviceIds().any { InputDevice.getDevice(it)?.name == name }) return true
            Thread.sleep(20)
        }
        return false
    }
}

object NativeBridge {
    external fun run(socket: String, uid: Int, token: String)
    external fun selfTest(): String
}
