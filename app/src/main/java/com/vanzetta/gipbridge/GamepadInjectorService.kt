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

    // One uinput fd per player slot (index 0 = player 1, 1 = player 2) instead of a single
    // fd — added for 2-controller support. GipBridgeService now opens each slot explicitly
    // via openDevice() once it knows a real controller is connected for that player, instead
    // of this service auto-opening one fixed device at construction time.
    private val MAX_PLAYERS = 2
    private val uinputFds = IntArray(MAX_PLAYERS) { -1 }

    // Shizuku ties this process's lifecycle to a clean unbind from the host app; an abnormal
    // host exit (force-stop, OOM kill on the TV box) skips that, orphaning this process forever
    // — Shizuku spawns a fresh one on the next bind rather than reusing or reaping the old one
    // (confirmed live: 3 stale :injector processes accumulated this way and killed rumble by
    // leaving it ambiguous which process's uinput device a game's FF events would reach).
    // This process already runs as shell UID (same as the orphans), so it can kill same-UID
    // siblings directly — same ProcessBuilder shell-out pattern as openSettings() below.
    private fun killStaleSiblings() {
        val myPid = android.os.Process.myPid()
        runCatching {
            val proc = ProcessBuilder(
                "sh", "-c",
                "for p in \$(pidof com.vanzetta.gipbridge:injector); do " +
                    "[ \"\$p\" != \"$myPid\" ] && kill -9 \"\$p\"; done",
            ).redirectErrorStream(true).start()
            proc.waitFor()
        }.onFailure { Log.e(TAG, "killStaleSiblings failed: ${it.message}") }
    }

    init {
        killStaleSiblings()
    }

    // Name must NOT contain the substring "Virtual" -- RetroArch's Android input driver
    // (input/drivers/android_input.c) hardcodes a special case that relabels any device
    // whose name contains "Virtual" as "SHIELD Virtual Controller" (meant for the Shield
    // remote's NVIDIA-button/CEC virtual device), which swallowed this controller's real
    // identity and made it indistinguishable from that unrelated system device — caller
    // (GipBridgeService) is responsible for picking a name that avoids that substring, same
    // as the existing default "8BitDo GIP Bridge Gamepad".
    override fun openDevice(playerIndex: Int, name: String): Boolean {
        if (playerIndex !in 0 until MAX_PLAYERS) { Log.e(TAG, "openDevice: bad playerIndex=$playerIndex"); return false }
        if (uinputFds[playerIndex] >= 0) { Log.d(TAG, "openDevice: player $playerIndex already open, reusing"); return true }
        val fd = runCatching { nativeOpenUinput(name) }.getOrElse { -1 }
        uinputFds[playerIndex] = fd
        Log.d(TAG, "uinput gamepad for player $playerIndex ('$name') fd=$fd")
        return fd >= 0
    }

    override fun closeDevice(playerIndex: Int) {
        if (playerIndex !in 0 until MAX_PLAYERS) return
        stopRumble(playerIndex)
        val fd = uinputFds[playerIndex]
        if (fd >= 0) nativeCloseUinput(fd)
        uinputFds[playerIndex] = -1
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

    override fun injectKey(playerIndex: Int, keyCode: Int, down: Boolean) {
        val btnCode = buttonMap[keyCode]
        val fd = uinputFds.getOrElse(playerIndex) { -1 }
        if (btnCode != null && fd >= 0) {
            nativeSendKey(fd, btnCode, down)
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
        playerIndex: Int,
        x: Float, y: Float, z: Float, rz: Float,
        ltrigger: Float, rtrigger: Float, hatX: Float, hatY: Float,
    ) {
        val fd = uinputFds.getOrElse(playerIndex) { -1 }
        if (fd < 0) return
        fun stick(v: Float) = (v * 32767f).toInt().coerceIn(-32768, 32767)
        fun trig(v: Float) = (v * 1023f).toInt().coerceIn(0, 1023)
        nativeSendAxes(
            fd,
            stick(x), stick(y), stick(z), stick(rz),
            trig(rtrigger), trig(ltrigger),
            hatX.toInt().coerceIn(-1, 1), hatY.toInt().coerceIn(-1, 1),
        )
    }

    // One poll thread per player slot — each uinput fd's FF events are independent, so a
    // single shared thread/flag (the original single-controller design) would only ever
    // service one player's rumble.
    private val rumblePolling = BooleanArray(MAX_PLAYERS)
    private val rumbleThreads = arrayOfNulls<Thread>(MAX_PLAYERS)

    override fun startRumble(playerIndex: Int, callback: IRumbleCallback) {
        if (playerIndex !in 0 until MAX_PLAYERS) { Log.e(TAG, "startRumble: bad playerIndex=$playerIndex"); return }
        val fd = uinputFds.getOrElse(playerIndex) { -1 }
        if (fd < 0) { Log.e(TAG, "startRumble: no uinput device for player $playerIndex"); return }
        if (rumblePolling[playerIndex]) return
        rumblePolling[playerIndex] = true
        rumbleThreads[playerIndex] = thread(name = "gip-ff-poll-p$playerIndex") {
            while (rumblePolling[playerIndex]) {
                val packed = nativePollFF(fd)
                if (packed < 0) { Thread.sleep(50); continue }
                val type = (packed ushr 60) and 0x3L
                if (type == 0L) continue
                val strong = (packed ushr 38) and 0xFFFFL
                val weak = (packed ushr 22) and 0xFFFFL
                val durationMs = (packed ushr 6) and 0xFFFFL
                runCatching {
                    if (type == 1L) {
                        val strongPct = (strong * 100 / 65535).toInt()
                        val weakPct = (weak * 100 / 65535).toInt()
                        // ff_replay.length==0 means "play until stopped" in the real Linux FF
                        // protocol — most game rumble effects don't rely on that and set a real
                        // length, but fall back to a short pulse instead of literally forever
                        // for the ones that do (an infinite rumble would be a real bug to ship).
                        callback.onRumble(strongPct, weakPct, if (durationMs > 0) durationMs.toInt() else 200)
                    } else {
                        callback.onRumbleStop()
                    }
                }.onFailure { Log.e(TAG, "rumble callback failed: ${it.message}") }
            }
        }
    }

    override fun stopRumble(playerIndex: Int) {
        if (playerIndex !in 0 until MAX_PLAYERS) return
        rumblePolling[playerIndex] = false
        rumbleThreads[playerIndex]?.interrupt()
        rumbleThreads[playerIndex] = null
    }

    // KEYCODE_SETTINGS injection was tried first and confirmed live to fire with no error, but
    // this Shield's launcher (FLauncher, not stock) apparently doesn't intercept that keycode
    // the way it does HOME — the foreground app (RetroArch) just swallows it, nothing opens.
    // Launching the Activity directly from this shell-UID process works instead: unlike the
    // main app process (a foreground Service with no visible window, confirmed blocked by
    // Android's background-activity-launch restriction), the shell UID has the same
    // activity-start privileges `adb shell am start` has used successfully throughout this
    // whole session.
    // Context.startActivity() from here fails with "Permission Denial: package=<app>
    // does not belong to uid=2000" -- confirmed live -- because this Context still carries
    // our app's registered package identity even though the process actually runs as shell
    // (uid 2000), and Android's ActivityManager checks that they match. Shelling out to the
    // real `am` binary instead sidesteps that entirely: it's an external process with its own
    // correctly-configured shell identity, doing the exact same AIDL call `adb shell am start`
    // has used successfully all session -- no Context/package mismatch involved at all.
    override fun openSettings() {
        runCatching {
            ProcessBuilder("am", "start", "-a", "android.settings.SETTINGS")
                .redirectErrorStream(true)
                .start()
        }.onFailure { Log.e(TAG, "openSettings failed: ${it.message}") }
    }

    override fun destroy() {
        for (i in 0 until MAX_PLAYERS) closeDevice(i)
    }
}
