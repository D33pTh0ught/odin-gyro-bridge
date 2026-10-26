package dev.gyrobridge

import android.app.Activity
import android.os.Bundle
import android.view.*
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only event receiver; absent from release builds. */
class InputProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(object : View(this) {
            init { isFocusableInTouchMode = true; requestFocus() }
            override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
                mice += Mouse(event.x, event.y, event.source); return true
            }
            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) mice += Mouse(event.x, event.y, event.source)
                return true
            }
        })
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        android.util.Log.i("GyroBridgeProbe", event.toString())
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            sticks += Stick(event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), event.deviceId)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keys += event.keyCode to event.action; return true
    }
    companion object {
        data class Stick(val x: Float, val y: Float, val deviceId: Int)
        data class Mouse(val x: Float, val y: Float, val source: Int)
        val sticks = CopyOnWriteArrayList<Stick>()
        val keys = CopyOnWriteArrayList<Pair<Int, Int>>()
        val mice = CopyOnWriteArrayList<Mouse>()
    }
}
