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
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlin.concurrent.thread

private const val TAG = "GipInjector"

// Real Linux BTN_* codes (from kernel uapi/linux/input-event-codes.h — verified against the
// real header, not assumed). Only gamepad buttons route here; system keys (HOME etc, used by
// the Xbox-button short-press feature) still go through the old injectInputEvent reflection
// path below, since a uinput joystick-class device has no path to trigger system Home/Recents.
private const val BTN_A = 0x130
private const val BTN_B = 0x131
private const val BTN_X = 0x133
private const val BTN_Y = 0x134
private const val BTN_TL = 0x136
private const val BTN_TR = 0x137
private const val BTN_START = 0x13b
private const val BTN_THUMBL = 0x13d
private const val BTN_THUMBR = 0x13e
// NOT BTN_SELECT (0x13a) — the real Xbox .kl file this device matched
// (/system/usr/keylayout/Vendor_045e_Product_02fd.kl, confirmed by pulling it off a live
// Shield) maps the View/Select button to raw KEY_BACK (158), not the joystick-range
// BTN_SELECT code. Sending 0x13a produced an event this .kl file has no rule for, so it
// never became a BUTTON_SELECT KeyEvent — confirmed live as the real cause of View not
// registering in RetroArch.
private const val KEY_BACK = 158

/**
 * Instantiated by Shizuku via reflection in a process it spawns under the ADB shell UID
 * (no-arg constructor required — Shizuku's contract, not ours). That UID already has
 * android.permission.INJECT_EVENTS natively, so InputManager.injectInputEvent works here
 * even though it would be refused (signature-permission, unGrantable to a normal app) if
 * called from the main app process. It's also the only UID on this device confirmed able to
 * open /dev/uinput (checked live — the app's own UID cannot).
 *
 * Gamepad buttons/axes now go through a real uinput virtual gamepad instead of
 * injectInputEvent: RetroArch's Android rumble path (`android_joypad_rumble` ->
 * `doVibrateJoypad`) is keyed by the real Android InputDevice id the button/axis events came
 * from, and explicitly skips vibration when that id is -1 ("device-vibration sentinel, not a
 * controller") — which is exactly what injectInputEvent's synthetic events resolve to. Only a
 * real evdev-backed InputDevice (via uinput) gives RetroArch something it'll actually
 * associate a vibrator with. Verified against RetroArch's own source
 * (input/drivers_joypad/android_joypad.c), not assumed.
 */
class GamepadInjectorService : IGamepadInjector.Stub() {

    private external fun nativeOpenUinput(name: String): Int
    private external fun nativeCloseUinput(fd: Int)
    private external fun nativeSendKey(fd: Int, code: Int, down: Boolean)
    private external fun nativeSendAxes(fd: Int, x: Int, y: Int, z: Int, rz: Int, gas: Int, brake: Int, hatX: Int, hatY: Int)
    private external fun nativePollFF(fd: Int): Long

    companion object {
        init {
            System.loadLibrary("gipuinput")
        }
    }

    private val inputManagerClass = Class.forName("android.hardware.input.InputManager")
    private val inputManagerInstance = inputManagerClass.getMethod("getInstance").invoke(null)
    private val injectMethod = inputManagerClass.getMethod(
        "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
    )
    private val MODE_ASYNC = 0

    private fun injectSystemEvent(event: InputEvent) {
        try {
            injectMethod.invoke(inputManagerInstance, event, MODE_ASYNC)
        } finally {
            if (event is MotionEvent) event.recycle()
        }
    }

    private var uinputFd: Int = -1

    init {
        uinputFd = runCatching { nativeOpenUinput("GIP Bridge Virtual Gamepad") }.getOrElse { -1 }
        Log.d(TAG, "uinput gamepad fd=$uinputFd")
    }

    private val buttonMap = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to BTN_A,
        KeyEvent.KEYCODE_BUTTON_B to BTN_B,
        KeyEvent.KEYCODE_BUTTON_X to BTN_X,
        KeyEvent.KEYCODE_BUTTON_Y to BTN_Y,
        KeyEvent.KEYCODE_BUTTON_L1 to BTN_TL,
        KeyEvent.KEYCODE_BUTTON_R1 to BTN_TR,
        KeyEvent.KEYCODE_BUTTON_THUMBL to BTN_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR to BTN_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START to BTN_START,
        KeyEvent.KEYCODE_BUTTON_SELECT to KEY_BACK,
    )

    override fun injectKey(keyCode: Int, down: Boolean) {
        val btnCode = buttonMap[keyCode]
        if (btnCode != null && uinputFd >= 0) {
            nativeSendKey(uinputFd, btnCode, down)
            return
        }
        // System keys (KEYCODE_HOME etc) — unchanged, confirmed-working reflection path.
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val ev = KeyEvent(
            now, now, action, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
        )
        injectSystemEvent(ev)
    }

    // GAS=right trigger, BRAKE=left trigger — Android's Generic.kl fallback (used because our
    // uinput device has no matching .kl file) maps analog triggers to GAS/BRAKE, not
    // LTRIGGER/RTRIGGER; Android's own JoystickInputMapper compat layer then derives
    // LTRIGGER/RTRIGGER from BRAKE/GAS for apps that read those instead. Not live-verified yet.
    override fun injectAxes(
        x: Float, y: Float, z: Float, rz: Float,
        ltrigger: Float, rtrigger: Float, hatX: Float, hatY: Float,
    ) {
        if (uinputFd < 0) return
        fun stick(v: Float) = (v * 32767f).toInt().coerceIn(-32768, 32767)
        fun trig(v: Float) = (v * 1023f).toInt().coerceIn(0, 1023)
        nativeSendAxes(
            uinputFd,
            stick(x), stick(y), stick(z), stick(rz),
            trig(rtrigger), trig(ltrigger),
            hatX.toInt().coerceIn(-1, 1), hatY.toInt().coerceIn(-1, 1),
        )
    }

    @Volatile private var rumblePolling = false
    private var rumbleThread: Thread? = null

    override fun startRumble(callback: IRumbleCallback) {
        if (uinputFd < 0) { Log.e(TAG, "startRumble: no uinput device"); return }
        if (rumblePolling) return
        rumblePolling = true
        rumbleThread = thread(name = "gip-ff-poll") {
            while (rumblePolling) {
                val packed = nativePollFF(uinputFd)
                if (packed < 0) { Thread.sleep(50); continue }
                val type = (packed ushr 48) and 0xFFFF
                if (type == 0L) continue
                val strong = (packed ushr 16) and 0xFFFF
                val weak = packed and 0xFFFF
                runCatching {
                    if (type == 1L) {
                        val strongPct = (strong * 100 / 65535).toInt()
                        val weakPct = (weak * 100 / 65535).toInt()
                        callback.onRumble(strongPct, weakPct, 0)
                    } else {
                        callback.onRumbleStop()
                    }
                }.onFailure { Log.e(TAG, "rumble callback failed: ${it.message}") }
            }
        }
    }

    override fun stopRumble() {
        rumblePolling = false
        rumbleThread?.interrupt()
        rumbleThread = null
    }

    // KEYCODE_SETTINGS injection was tried first and confirmed live to fire with no error, but
    // this Shield's launcher (FLauncher, not stock) apparently doesn't intercept that keycode
    // the way it does HOME — the foreground app (RetroArch) just swallows it, nothing opens.
    // Launching the Activity directly from this shell-UID process works instead: unlike the
    // main app process (a foreground Service with no visible window, confirmed blocked by
    // Android's background-activity-launch restriction), the shell UID has the same
    // activity-start privileges `adb shell am start` has used successfully throughout this
    // whole session.
    override fun openSettings() {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val context = activityThread.getMethod("currentApplication").invoke(null) as Context
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "openSettings failed: ${it.message}") }
    }

    override fun destroy() {
        stopRumble()
        if (uinputFd >= 0) {
            nativeCloseUinput(uinputFd)
            uinputFd = -1
        }
    }
}
