package com.vanzetta.gipbridge

import android.hardware.usb.UsbDevice
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Instantiated by Shizuku via reflection in a process it spawns under the ADB shell UID
 * (no-arg constructor required — Shizuku's contract, not ours). That UID already has
 * android.permission.INJECT_EVENTS natively, so InputManager.injectInputEvent works here
 * even though it would be refused (signature-permission, unGrantable to a normal app) if
 * called from the main app process.
 *
 * injectInputEvent is @UnsupportedAppUsage (hidden but present) on the public InputManager
 * class, so it's reached via reflection rather than a compileSdk-visible call.
 */
class GamepadInjectorService : IGamepadInjector.Stub() {

    private val inputManagerClass = Class.forName("android.hardware.input.InputManager")
    private val inputManagerInstance = inputManagerClass.getMethod("getInstance").invoke(null)
    private val injectMethod = inputManagerClass.getMethod(
        "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
    )

    // INJECT_INPUT_EVENT_MODE_ASYNC — don't block waiting for the event to be consumed.
    private val MODE_ASYNC = 0

    private fun inject(event: InputEvent) {
        try {
            injectMethod.invoke(inputManagerInstance, event, MODE_ASYNC)
        } finally {
            if (event is MotionEvent) event.recycle()
        }
    }

    override fun injectKey(keyCode: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val ev = KeyEvent(
            now, now, action, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
        )
        inject(ev)
    }

    override fun injectAxes(
        x: Float, y: Float, z: Float, rz: Float,
        ltrigger: Float, rtrigger: Float, hatX: Float, hatY: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, x)
            setAxisValue(MotionEvent.AXIS_Y, y)
            setAxisValue(MotionEvent.AXIS_Z, z)
            setAxisValue(MotionEvent.AXIS_RZ, rz)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, ltrigger)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, rtrigger)
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
        })
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0,
        )
        inject(ev)
    }

    override fun destroy() {
        // no held resources
    }
}
