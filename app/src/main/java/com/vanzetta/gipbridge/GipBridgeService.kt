package com.vanzetta.gipbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

private const val TAG = "GipBridge"
private const val ACTION_USB_PERMISSION = "com.vanzetta.gipbridge.USB_PERMISSION"
private const val ACTION_TEST_RUMBLE = "com.vanzetta.gipbridge.TEST_RUMBLE"
private const val ACTION_TEST_SELECT_HOLD = "com.vanzetta.gipbridge.TEST_SELECT_HOLD"
private const val ACTION_TEST_NAV = "com.vanzetta.gipbridge.TEST_NAV"
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4242
private const val XBOX_LONG_PRESS_MS = 500L
private const val INPUT_LOG_THROTTLE_MS = 200L
private const val NOTIF_CHANNEL_ID = "gip_bridge_service"
private const val NOTIF_ID = 1

/**
 * Owns the USB session, Shizuku injector binding, and G733 lights control as a foreground
 * service — running this as plain background work inside the Activity meant Android TV's
 * process management killed/froze the reader thread the moment the app was backgrounded.
 * A foreground service (with its required persistent notification) is exempt from that.
 */
class GipBridgeService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): GipBridgeService = this@GipBridgeService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val logHistory = StringBuilder()
    @Volatile private var logListener: ((String) -> Unit)? = null
    fun setLogListener(listener: ((String) -> Unit)?) { logListener = listener }
    fun getLogHistory(): String = synchronized(logHistory) { logHistory.toString() }

    private lateinit var usbManager: UsbManager
    private var readerThread: Thread? = null
    @Volatile private var running = false
    private var gipConnection: UsbDeviceConnection? = null
    private var gipEpOut: UsbEndpoint? = null

    // Read fresh each call (not cached) so a config change via SettingsActivity takes
    // effect on the next USB attach event without needing a service restart.
    private fun isController(device: UsbDevice) =
        device.vendorId == DeviceConfig.controllerVid(this) && device.productId == DeviceConfig.controllerPid(this)
    private fun isHeadset(device: UsbDevice) =
        device.vendorId == DeviceConfig.headsetVid(this) && device.productId == DeviceConfig.headsetPid(this)

    @Volatile private var injector: IGamepadInjector? = null
    private var lastButtons = 0
    // Analog axes wobble every poll during active stick movement, so equality-based dedup
    // (like sendRumble's) never suppresses anything here — throttle by time instead. Unthrottled
    // this was Log.d + StringBuilder append at ~250Hz while a stick was held off-center.
    private var lastInputLogAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var xboxLongPressFired = false
    private val xboxLongPressRunnable = Runnable {
        xboxLongPressFired = true
        log("XBOX BUTTON: held -> opening Settings")
        // Confirmed live: startActivity(ACTION_SETTINGS) from here gets silently blocked by
        // Android's background-activity-launch restriction ("Background activity start...
        // isCallingUidForeground: false") — a foreground Service has no visible window, so it
        // doesn't qualify to launch an Activity directly. Tried KEYCODE_SETTINGS injection next
        // (works for HOME) but this Shield's launcher doesn't intercept that keycode, so
        // RetroArch just swallowed it. Launching from the Shizuku shell-UID process instead —
        // that UID has real activity-start privileges.
        runCatching {
            injector?.openSettings()
        }.onFailure { log("Settings launch failed: ${it.message}") }
    }

    // Bump this on every release that touches GamepadInjectorService/uinput_gamepad.c --
    // Shizuku reuses a cached injector process across app updates when this doesn't change,
    // which silently kept old native code running through several rebuilds during
    // development (real bug hunted down live, cost real debugging time more than once).
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, GamepadInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("injector").debuggable(false).version(2)

    // Runs on a binder thread from the Shizuku process's FF poll loop. durationMs is currently
    // always 0 from GamepadInjectorService (ff_replay.length parsing deferred) — treated as
    // "play until stop", matched with a short repeat/rearm window since GIP rumble packets are
    // one-shot with an explicit duration, not a persistent on/off state like Linux FF play/stop.
    // Confirmed live in Gran Turismo: the core re-uploads/re-plays the rumble effect on a
    // timer (~65ms interval) instead of one long-running effect — normal for a "continuous
    // while accelerating" engine sound tied to RPM. Each play already carries its own hardware
    // duration, so sending an explicit motor-stop between every refresh hard-cuts the motor
    // and immediately restarts it, which feels like stuttering/"rumbling like crazy" instead of
    // smooth continuous rumble. Debouncing the stop: delay it briefly and cancel if a new
    // onRumble() arrives first, so back-to-back refreshes never actually zero the motor.
    private var pendingRumbleStop: Runnable? = null
    private val rumbleCallback = object : IRumbleCallback.Stub() {
        override fun onRumble(strongPercent: Int, weakPercent: Int, durationMs: Int) {
            pendingRumbleStop?.let { mainHandler.removeCallbacks(it) }
            pendingRumbleStop = null
            // Weak (small/high-freq) motor only. Real game rumble stays off the trigger
            // motors — Linux's standard FF_RUMBLE effect (what RetroArch/games actually send)
            // only has 2 channels with no way to address triggers separately anyway. Also off
            // the strong (large/low-freq) motor by request: confirmed live in Gran Turismo that
            // RetroArch 1.22.2 OR-merges strong+weak into one identical value for both
            // channels (its own dual-motor code path doesn't exist in this version — confirmed
            // against the real installed build's source), so both motors were firing at equal
            // magnitude — the weak motor alone reads as noticeably gentler at the same value.
            sendRumble(strongPercent, weakPercent, if (durationMs > 0) durationMs else 200, GipMotor.RIGHT)
        }
        override fun onRumbleStop() {
            pendingRumbleStop?.let { mainHandler.removeCallbacks(it) }
            val stop = Runnable {
                sendRumble(0, 0, 0, GipMotor.RIGHT)
                pendingRumbleStop = null
            }
            pendingRumbleStop = stop
            mainHandler.postDelayed(stop, 150)
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            injector = IGamepadInjector.Stub.asInterface(binder)
            log("Shizuku injector service connected.")
            runCatching { injector?.startRumble(rumbleCallback) }
                .onFailure { log("startRumble registration failed: ${it.message}") }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            injector = null
            log("Shizuku injector service disconnected.")
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted, binding injector service...")
            bindInjector()
        } else {
            log("Shizuku permission DENIED — system-wide injection unavailable.")
        }
    }

    // Force-unbind (destroy=true) before every bind — covers the binder-died-but-app-alive
    // case (same userServiceConnection still holds a live binding to destroy). Does NOT cover
    // orphans left by a full app process restart (force-stop, OOM kill): a fresh process has
    // nothing to unbind, so this is a no-op there — confirmed live, still leaves stale
    // :injector processes running. That case is handled by killStaleSiblings() in
    // GamepadInjectorService instead, which runs as the same shell UID as the orphans.
    private fun bindInjector() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        Shizuku.bindUserService(userServiceArgs, userServiceConnection)
    }

    private fun setupShizuku() {
        if (Shizuku.isPreV11()) { log("Shizuku pre-v11, unsupported."); return }
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindInjector()
            Shizuku.shouldShowRequestPermissionRationale() ->
                log("Shizuku permission previously denied by user.")
            else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            synchronized(this) {
                val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    log("USB permission granted for ${device.deviceName}")
                    if (isHeadset(device)) startG733Session(device)
                    else if (isController(device)) startGipSession(device)
                } else {
                    log("USB permission DENIED")
                }
            }
        }
    }

    // Fires a real GIP rumble packet straight at the controller, bypassing uinput/FF/RetroArch
    // entirely — isolates whether a "rumble doesn't work" report is our GIP packet path or
    // the RetroArch/Android InputDevice-vibrator integration layer.
    private val testRumbleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            log("Test rumble sequence: large motor, small motor, left trigger, right trigger, all together.")
            thread(name = "test-rumble-sequence") {
                fun pulse(label: String, motors: Int) {
                    log("Test rumble: $label")
                    sendRumble(100, 100, 700, motors)
                    Thread.sleep(700)
                    sendRumble(0, 0, 0, motors)
                    Thread.sleep(600)
                }
                pulse("LEFT (large/strong) motor only", GipMotor.LEFT)
                pulse("RIGHT (small/weak) motor only", GipMotor.RIGHT)
                pulse("LEFT TRIGGER motor only", GipMotor.TRIGGER_LEFT)
                pulse("RIGHT TRIGGER motor only", GipMotor.TRIGGER_RIGHT)
                pulse("ALL motors together", GipMotor.ALL)
            }
        }
    }

    // Holds SELECT through our own uinput device for real, the same path a physical press
    // takes — adb's `input keyevent` injects via a totally different, generic system-level
    // path (not through our device's real InputDevice id at all), which is almost certainly
    // why remote testing of RetroArch's SELECT-hold menu combo (input_menu_toggle_gamepad_combo
    // = HOLD_SELECT, confirmed from the live cfg) never worked.
    private val testSelectHoldReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            log("Test SELECT hold (via real uinput device) triggered.")
            thread(name = "test-select-hold") {
                runCatching {
                    injector?.injectKey(KeyEvent.KEYCODE_BUTTON_SELECT, true)
                    Thread.sleep(2500)
                    injector?.injectKey(KeyEvent.KEYCODE_BUTTON_SELECT, false)
                }.onFailure { log("Test SELECT hold failed: ${it.message}") }
            }
        }
    }

    // Generic remote nav test — extra "key" one of UP/DOWN/LEFT/RIGHT/A/B/START/SELECT,
    // injected through the real uinput device (same path as a physical press) so RetroArch's
    // menu can actually be driven blind via adb, using screenshots for feedback instead of a
    // physically-present controller.
    private val testNavReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val key = intent.getStringExtra("key") ?: return
            log("Test nav: $key")
            val inj = injector ?: return
            runCatching {
                when (key) {
                    "UP" -> { inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, -1f); Thread.sleep(150); inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) }
                    "DOWN" -> { inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f); Thread.sleep(150); inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) }
                    "LEFT" -> { inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, -1f, 0f); Thread.sleep(150); inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) }
                    "RIGHT" -> { inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f); Thread.sleep(150); inj.injectAxes(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) }
                    "A" -> { inj.injectKey(KeyEvent.KEYCODE_BUTTON_A, true); Thread.sleep(100); inj.injectKey(KeyEvent.KEYCODE_BUTTON_A, false) }
                    "B" -> { inj.injectKey(KeyEvent.KEYCODE_BUTTON_B, true); Thread.sleep(100); inj.injectKey(KeyEvent.KEYCODE_BUTTON_B, false) }
                    "START" -> { inj.injectKey(KeyEvent.KEYCODE_BUTTON_START, true); Thread.sleep(100); inj.injectKey(KeyEvent.KEYCODE_BUTTON_START, false) }
                    "SELECT" -> { inj.injectKey(KeyEvent.KEYCODE_BUTTON_SELECT, true); Thread.sleep(100); inj.injectKey(KeyEvent.KEYCODE_BUTTON_SELECT, false) }
                }
            }.onFailure { log("Test nav failed: ${it.message}") }
        }
    }

    private val testEnableRumbleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DeviceConfig.setRumbleEnabled(this@GipBridgeService, true)
            log("Rumble force-enabled via broadcast. Now: ${DeviceConfig.rumbleEnabled(this@GipBridgeService)}")
        }
    }

    private fun registerTestReceivers() {
        val testRumbleFilter = IntentFilter(ACTION_TEST_RUMBLE)
        val testSelectHoldFilter = IntentFilter(ACTION_TEST_SELECT_HOLD)
        val testNavFilter = IntentFilter(ACTION_TEST_NAV)
        val testEnableRumbleFilter = IntentFilter("com.vanzetta.gipbridge.TEST_ENABLE_RUMBLE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testRumbleReceiver, testRumbleFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(testSelectHoldReceiver, testSelectHoldFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(testNavReceiver, testNavFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(testEnableRumbleReceiver, testEnableRumbleFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(testRumbleReceiver, testRumbleFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(testSelectHoldReceiver, testSelectHoldFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(testNavReceiver, testNavFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(testEnableRumbleReceiver, testEnableRumbleFilter)
        }
    }

    private fun unregisterTestReceivers() {
        runCatching { unregisterReceiver(testRumbleReceiver) }
        runCatching { unregisterReceiver(testSelectHoldReceiver) }
        runCatching { unregisterReceiver(testNavReceiver) }
        runCatching { unregisterReceiver(testEnableRumbleReceiver) }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            if (device != null && (isController(device) || isHeadset(device))) {
                log("Attach event: ${device.deviceName} (vid=${device.vendorId} pid=${device.productId})")
                requestPermissionAndConnect(device)
            }
        }
    }

    // Without this, unplugging mid-session leaves the read loop spinning on repeated
    // failed transfers with nothing telling it to stop — the connection/interface are
    // gone but `running`/`g733Running` stay true until the app itself is killed.
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            if (device == null) return
            if (isController(device)) {
                log("Controller detached.")
                stopGipSession()
            } else if (isHeadset(device)) {
                log("Headset detached.")
                stopG733Session()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        startInForeground()

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, attachFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbDetachReceiver, detachFilter)
        }
        // Debug-only remote test/diagnostic hooks (Test Rumble, SELECT-hold, nav, force-enable
        // rumble) — real dev tooling built during bring-up, not part of the shipped feature
        // set. Gated out of release builds entirely rather than just left in unused.
        if (BuildConfig.DEBUG) registerTestReceivers()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky { setupShizuku() }

        log(
            "GIP Bridge service started. Looking for controller " +
                "(vid=${DeviceConfig.controllerVid(this)} pid=${DeviceConfig.controllerPid(this)}) " +
                "and headset (vid=${DeviceConfig.headsetVid(this)} pid=${DeviceConfig.headsetPid(this)})..."
        )
        findAndConnectExisting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "GIP Bridge", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("GIP Bridge active")
            .setContentText("8BitDo controller + G733 lights bridge running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun findAndConnectExisting() {
        val targets = usbManager.deviceList.values.filter { isController(it) || isHeadset(it) }
        if (targets.isEmpty()) {
            log("No target device currently attached. Plug it in (attach receiver will catch it).")
            return
        }
        for (target in targets) {
            log("Found already-attached device: ${target.deviceName}")
            requestPermissionAndConnect(target)
        }
    }

    private fun requestPermissionAndConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            if (isHeadset(device)) startG733Session(device) else if (isController(device)) startGipSession(device)
            return
        }
        // Confirmed live: requesting permission while nothing is in the foreground (TV
        // screensaver active) can leave the request permanently stuck with no dialog ever
        // shown — the system needs a foreground surface to anchor the prompt to. Bring the
        // app forward first so the dialog always has somewhere to attach.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
        log("Requested USB permission, waiting for user/system response...")
    }

    private fun startGipSession(device: UsbDevice) {
        if (running) { log("Session already running, ignoring duplicate start."); return }

        // Interface 0 is the interrupt-based GIP command/input channel (captured live via
        // `adb shell dumpsys usb`: class=255 subclass=71 protocol=208, IN ep addr=0x82,
        // OUT ep addr=0x02, both type=Interrupt maxPacketSize=64). Interface 1 (isochronous)
        // is the analog audio passthrough for the headphone jack — not needed here.
        val iface: UsbInterface? = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == 0 }
        if (iface == null) { log("ERROR: interface 0 not found"); return }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
        }
        if (epIn == null || epOut == null) { log("ERROR: expected IN+OUT endpoints not found on interface 0"); return }

        val connection: UsbDeviceConnection? = usbManager.openDevice(device)
        if (connection == null) { log("ERROR: openDevice failed"); return }
        if (!connection.claimInterface(iface, true)) { log("ERROR: claimInterface failed"); return }

        // xone's wired driver explicitly re-asserts interface 1 (audio) alt-setting 0 —
        // "mandatory for certain third party devices" per their own comment — even though
        // it's already the default alt-setting. Mimicking that as a possible wake signal.
        val audioIface: UsbInterface? = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == 1 && it.alternateSetting == 0 }
        if (audioIface != null) {
            val claimed = connection.claimInterface(audioIface, true)
            log("Interface 1 (audio) alt=0 claim: $claimed")
            if (claimed) {
                val setOk = connection.setInterface(audioIface)
                log("setInterface(1, alt=0) result: $setOk")
            }
        } else {
            log("Interface 1 alt=0 not found (unexpected)")
        }

        log("Interface claimed. IN ep=0x${epIn.address.toString(16)} OUT ep=0x${epOut.address.toString(16)}. Starting read loop...")
        gipConnection = connection
        gipEpOut = epOut
        running = true
        readerThread = thread(name = "gip-reader") { readLoop(connection, epIn, epOut) }
    }

    private fun stopGipSession() {
        running = false
        readerThread?.join(500)
        runCatching { gipConnection?.close() }
        gipConnection = null
        gipEpOut = null
        readerThread = null
    }

    private fun readLoop(conn: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint) {
        val buf = ByteArray(256)
        var packetsSeen = 0
        var inputPacketsSeen = 0
        while (running) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 2000)
            // n<=0 also happens on a genuine detach (immediate failure, not a timeout) —
            // a short sleep here guarantees this never busy-spins regardless of which.
            if (n <= 0) { Thread.sleep(50); continue }

            packetsSeen++

            val data = buf.copyOf(n)
            val (hdr, hdrLen) = try {
                decodeGipHeader(data)
            } catch (e: Exception) {
                log("decode error on ${n}B packet: ${e.message} raw=${data.toHex()}")
                continue
            }
            val payload = if (data.size > hdrLen) data.copyOfRange(hdrLen, data.size) else ByteArray(0)

            when {
                hdr.isInternal && hdr.command == GipCommand.ANNOUNCE -> {
                    val ann = parseAnnounce(payload)
                    log("ANNOUNCE: vid=${ann?.vendorId} pid=${ann?.productId} fw=${ann?.fwMajor}.${ann?.fwMinor}.${ann?.fwBuild}.${ann?.fwRevision} needsAck=${hdr.needsAck}")
                    sendAck(conn, epOut, hdr)
                    sendPowerOn(conn, epOut)
                    sendIdentifyRequest(conn, epOut)
                }
                hdr.isInternal && hdr.command == GipCommand.STATUS -> {
                    log("STATUS: ${payload.toHex()} needsAck=${hdr.needsAck}")
                    sendAck(conn, epOut, hdr)
                }
                hdr.isInternal && hdr.command == GipCommand.VIRTUAL_KEY -> {
                    if (payload.size >= 2) {
                        val down = payload[0].toInt() != 0
                        val key = payload[1].toInt() and 0xFF
                        if (key == 0x5b) {
                            log("XBOX BUTTON: ${if (down) "DOWN" else "UP"}")
                            if (down) {
                                xboxLongPressFired = false
                                mainHandler.postDelayed(xboxLongPressRunnable, XBOX_LONG_PRESS_MS)
                            } else {
                                mainHandler.removeCallbacks(xboxLongPressRunnable)
                                if (!xboxLongPressFired) {
                                    runCatching {
                                        injector?.injectKey(KeyEvent.KEYCODE_HOME, true)
                                        injector?.injectKey(KeyEvent.KEYCODE_HOME, false)
                                    }.onFailure { log("inject XBOX button failed: ${it.message}") }
                                }
                            }
                        } else log("virtual key 0x${key.toString(16)}: ${if (down) "DOWN" else "UP"}")
                    }
                    sendAck(conn, epOut, hdr)
                }
                hdr.isInternal -> {
                    log("internal cmd=0x${hdr.command.toString(16)} opts=0x${hdr.options.toString(16)} len=${payload.size} raw=${payload.toHex()} needsAck=${hdr.needsAck}")
                    sendAck(conn, epOut, hdr)
                }
                hdr.command == GipCommand.INPUT -> {
                    inputPacketsSeen++
                    val state = parseGamepadInput(payload)
                    if (state != null && (state.buttons != 0 || state.triggerLeft != 0 || state.triggerRight != 0 ||
                            kotlin.math.abs(state.stickLeftX) > 3000 || kotlin.math.abs(state.stickLeftY) > 3000 ||
                            kotlin.math.abs(state.stickRightX) > 3000 || kotlin.math.abs(state.stickRightY) > 3000)) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastInputLogAtMs >= INPUT_LOG_THROTTLE_MS) {
                            log("INPUT: ${state.describe()}")
                            lastInputLogAtMs = now
                        }
                    } else if (inputPacketsSeen <= 2) {
                        log("INPUT #$inputPacketsSeen idle (${payload.size}B)")
                    }
                    if (state != null) {
                        runCatching { injectGamepadState(state) }
                            .onFailure { log("inject failed: ${it.message}") }
                    }
                }
                else -> {
                    log("unhandled cmd=0x${hdr.command.toString(16)} opts=0x${hdr.options.toString(16)} raw=${payload.toHex()}")
                }
            }
        }
        log("Read loop stopped after $packetsSeen packets ($inputPacketsSeen INPUT).")
    }

    private fun injectGamepadState(state: GamepadState) {
        val inj = injector ?: return

        val changed = state.buttons xor lastButtons
        if (changed != 0) {
            fun key(mask: Int, code: Int) {
                if (changed and mask != 0) inj.injectKey(code, state.buttons and mask != 0)
            }
            key(GipButton.A, KeyEvent.KEYCODE_BUTTON_A)
            key(GipButton.B, KeyEvent.KEYCODE_BUTTON_B)
            key(GipButton.X, KeyEvent.KEYCODE_BUTTON_X)
            key(GipButton.Y, KeyEvent.KEYCODE_BUTTON_Y)
            key(GipButton.BUMPER_L, KeyEvent.KEYCODE_BUTTON_L1)
            key(GipButton.BUMPER_R, KeyEvent.KEYCODE_BUTTON_R1)
            key(GipButton.STICK_L, KeyEvent.KEYCODE_BUTTON_THUMBL)
            key(GipButton.STICK_R, KeyEvent.KEYCODE_BUTTON_THUMBR)
            key(GipButton.MENU, KeyEvent.KEYCODE_BUTTON_START)
            key(GipButton.VIEW, KeyEvent.KEYCODE_BUTTON_SELECT)
            lastButtons = state.buttons
        }

        val hatX = when {
            state.buttons and GipButton.DPAD_LEFT != 0 -> -1f
            state.buttons and GipButton.DPAD_RIGHT != 0 -> 1f
            else -> 0f
        }
        val hatY = when {
            state.buttons and GipButton.DPAD_UP != 0 -> -1f
            state.buttons and GipButton.DPAD_DOWN != 0 -> 1f
            else -> 0f
        }
        inj.injectAxes(
            state.stickLeftX / 32767f,
            -state.stickLeftY / 32767f, // confirmed live: inverted (down read as up) via the real uinput device — old injectInputEvent path didn't have this issue
            state.stickRightX / 32767f,
            -state.stickRightY / 32767f, // same signed-range calibration path as left stick Y — untested, predicted to need the same fix, please confirm
            state.triggerLeft / 1023f,
            state.triggerRight / 1023f,
            hatX,
            hatY,
        )
    }

    private var seq = 0
    private fun nextSeq(): Int { seq = (seq + 1) and 0xFF; if (seq == 0) seq = 1; return seq }

    private fun sendAck(conn: UsbDeviceConnection, epOut: UsbEndpoint, acked: GipHeader) {
        val payload = buildAcknowledgePayload(acked.command, clientId = 0, totalLen = acked.packetLength)
        val hdr = GipHeader(
            command = GipCommand.ACKNOWLEDGE,
            options = GipOption.INTERNAL,
            sequence = acked.sequence,
            packetLength = payload.size,
        )
        writePacket(conn, epOut, hdr, payload)
    }

    private fun sendPowerOn(conn: UsbDeviceConnection, epOut: UsbEndpoint) {
        val payload = byteArrayOf(0x00) // GIP_PWR_ON
        val hdr = GipHeader(
            command = GipCommand.POWER,
            options = GipOption.INTERNAL,
            sequence = nextSeq(),
            packetLength = payload.size,
        )
        writePacket(conn, epOut, hdr, payload)
        log("Sent POWER=ON")
    }

    private fun sendIdentifyRequest(conn: UsbDeviceConnection, epOut: UsbEndpoint) {
        val hdr = GipHeader(
            command = GipCommand.IDENTIFY,
            options = GipOption.INTERNAL,
            sequence = nextSeq(),
            packetLength = 0,
        )
        writePacket(conn, epOut, hdr, ByteArray(0))
        log("Sent IDENTIFY request")
    }

    // strongPercent/weakPercent are 0-100, scaled down further by the user's configured
    // rumble strength. durationMs=0 sends an explicit stop (all motors off).
    private var lastLoggedRumbleState: Triple<Int, Int, Int>? = null

    private fun sendRumble(strongPercent: Int, weakPercent: Int, durationMs: Int, motors: Int = GipMotor.ALL) {
        val conn = gipConnection ?: run { log("sendRumble: no gipConnection, skipping"); return }
        val epOut = gipEpOut ?: run { log("sendRumble: no gipEpOut, skipping"); return }
        if (durationMs > 0 && !DeviceConfig.rumbleEnabled(this)) { log("sendRumble: rumble disabled in settings, skipping"); return }

        val userStrength = DeviceConfig.rumbleStrength(this)
        val left = (strongPercent * userStrength / 100).coerceIn(0, 100)
        val right = (weakPercent * userStrength / 100).coerceIn(0, 100)
        val durationTens = (durationMs / 10).coerceIn(0, 255)

        val payload = buildRumblePayload(motors = motors, leftTrigger = left, rightTrigger = right, left = left, right = right, durationTens = durationTens)
        // Log only on real state change, not every packet — a sustained rumble effect (e.g.
        // GT's continuous throttle buzz) resends the same magnitude ~15x/sec, and logging
        // every single one buries real signal in noise for no benefit once the state is known.
        val state = Triple(motors, left, right)
        if (state != lastLoggedRumbleState) {
            log("sendRumble: motors=0x${motors.toString(16)} left=$left right=$right durationTens=$durationTens payload=${payload.toHex()}")
            lastLoggedRumbleState = state
        }
        val hdr = GipHeader(
            command = GipCommand.RUMBLE,
            options = 0, // per xone's gip_send_rumble: clientId only, no INTERNAL flag
            sequence = nextSeq(),
            packetLength = payload.size,
        )
        writePacket(conn, epOut, hdr, payload)
    }

    private fun writePacket(conn: UsbDeviceConnection, epOut: UsbEndpoint, hdr: GipHeader, payload: ByteArray) {
        val headerBytes = encodeGipHeader(hdr)
        val out = headerBytes + payload
        val n = conn.bulkTransfer(epOut, out, out.size, 1000)
        if (n < 0) log("WARN: write failed for cmd=0x${hdr.command.toString(16)}")
    }

    // G733 Lightspeed dongle HID interface (class=3) — captured live via `adb shell dumpsys
    // usb`: only an IN endpoint (0x83), no OUT endpoint, so output reports must go via a
    // SET_REPORT control transfer rather than a bulk/interrupt write. Protocol (report id
    // 0x11, feature 0x04, sub-command 0x3e, side byte, mode byte) reverse-engineered by
    // github.com/YulCmr/G733_windows_app against real hardware — the dongle has no
    // persistent memory for this, so it must be re-sent every time it's plugged in/powered on.
    @Volatile private var g733Running = false
    private var g733ReaderThread: Thread? = null
    private var g733Connection: UsbDeviceConnection? = null
    private var g733HidIface: UsbInterface? = null
    @Volatile private var g733AnnouncePending = false

    private fun startG733Session(device: UsbDevice) {
        if (g733Running) { log("G733 session already running, ignoring duplicate start."); return }

        val hidIface: UsbInterface? = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        if (hidIface == null) { log("G733: HID interface not found"); return }

        // Captured live via `adb shell dumpsys usb`: interface class=3 (HID) has only an
        // IN endpoint (address 0x83) — no OUT endpoint, so output reports (lights, battery
        // query) go via a SET_REPORT control transfer, but reading responses (battery level,
        // power state) needs this IN endpoint same as any other USB read.
        var epIn: UsbEndpoint? = null
        for (i in 0 until hidIface.endpointCount) {
            val ep = hidIface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
        }
        if (epIn == null) { log("G733: IN endpoint not found"); return }

        val connection = usbManager.openDevice(device)
        if (connection == null) { log("G733: openDevice failed"); return }
        if (!connection.claimInterface(hidIface, true)) { log("G733: claimInterface failed"); return }

        g733Connection = connection
        g733HidIface = hidIface

        sendG733LightsOff(connection, hidIface, 0x00) // bottom zone
        sendG733LightsOff(connection, hidIface, 0x01) // top zone
        sendG733BatteryQuery(connection, hidIface)

        log("G733 session started. IN ep=0x${epIn.address.toString(16)}. Listening for status reports...")
        g733Running = true
        g733ReaderThread = thread(name = "g733-reader") { g733ReadLoop(connection, epIn) }
    }

    private fun stopG733Session() {
        g733Running = false
        g733ReaderThread?.join(500)
        runCatching { g733Connection?.close() }
        g733Connection = null
        g733HidIface = null
        g733ReaderThread = null
        g733BatteryToast?.cancel()
    }

    private fun g733ReadLoop(conn: UsbDeviceConnection, epIn: UsbEndpoint) {
        val buf = ByteArray(64)
        while (g733Running) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 2000)
            // n<=0 also happens on a genuine detach (immediate failure, not a timeout) —
            // a short sleep here guarantees this never busy-spins regardless of which.
            if (n <= 0) { Thread.sleep(50); continue }
            val data = buf.copyOf(n)
            parseG733Report(data)
        }
        log("G733 read loop stopped.")
    }

    // Protocol (report id 0x11, feature 0x08 = battery, sub-command 0x0f = query) and the
    // voltage->percent formula reverse-engineered by github.com/YulCmr/G733_windows_app
    // against real hardware. Confirmed live: this dongle only returns real telemetry after
    // the headset's wireless link has been freshly (re)established — querying while the
    // link is idle gets a fixed echo/ack instead of live voltage.
    //
    // IMPORTANT: this interface echoes back EVERY command we send (confirmed live for both
    // the battery query and the lights-off command). An earlier version of this function
    // treated "anything that isn't a battery response" as a power/link event — which caught
    // our own echoes too, and since reacting to an event means SENDING more commands, that
    // caused an infinite feedback loop (echo -> treated as event -> resend -> new echo ->
    // ...). Fixed by allowlisting only the specific byte patterns actually observed
    // correlating with a physical power-switch toggle, rather than blocklisting echoes
    // (which would need updating every time a new command is added here).
    private fun parseG733Report(data: ByteArray) {
        val isBatteryResponse = data.size >= 7 &&
            (data[2].toInt() and 0xFF) == 0x08 && (data[3].toInt() and 0xF0) == 0x00
        // Captured live coinciding with physical power-switch toggles: a short 5-byte
        // `01 00 00 00 00`, and a pair of 20-byte reports with feature byte 0x05.
        val isPowerEvent = data.size <= 5 ||
            (data.size >= 3 && (data[2].toInt() and 0xFF) == 0x05)

        if (isBatteryResponse) {
            val voltage = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val plugged = when (data[6].toInt() and 0xFF) {
                0x01 -> false
                0x03, 0x07 -> true
                else -> null
            }
            if (voltage == 0) { log("G733: battery report but voltage=0 (disconnected?)"); return }
            val percent = (0.1667 * voltage - 608.33).toInt().coerceIn(0, 100)
            log("G733 BATTERY: ${percent}% (${voltage}mV)${if (plugged == true) " [charging]" else ""}")
            if (g733AnnouncePending) {
                g733AnnouncePending = false
                announceG733Battery(percent, plugged == true)
            }
        } else if (isPowerEvent) {
            log("G733 power event: ${data.toHex()}")
            val conn = g733Connection
            val iface = g733HidIface
            if (conn != null && iface != null) {
                // The headset forgets lights-off on every power cycle, not just a dongle
                // unplug/replug — re-apply it on the same link event used for the battery
                // announce, so turning the headset off and back on doesn't bring lights back.
                //
                // A control-transfer "success" here only confirms the dongle received the
                // command, not that the wireless RF link to the headset itself has finished
                // re-establishing (confirmed live: this event can fire while a battery query
                // still reads voltage=0/disconnected). Sending immediately can silently get
                // dropped, so send now AND again after a delay once the link has had time to
                // settle — same double-send used at initial connect, just retried.
                runCatching { sendG733LightsOff(conn, iface, 0x00) }
                runCatching { sendG733LightsOff(conn, iface, 0x01) }
                g733AnnouncePending = true
                runCatching { sendG733BatteryQuery(conn, iface) }

                thread(name = "g733-lights-retry") {
                    Thread.sleep(2000)
                    if (g733Running) {
                        runCatching { sendG733LightsOff(conn, iface, 0x00) }
                        runCatching { sendG733LightsOff(conn, iface, 0x01) }
                    }
                }
            }
        } else {
            // Echo of our own command, or an unrecognized report — log only, no action.
            // Reacting here (sending more commands) is what caused the earlier feedback loop.
            log("G733 report: ${data.toHex()}")
        }
    }

    private var g733BatteryToast: Toast? = null

    // Toasts shown from a Service (no foreground Activity actively displaying it) can fail
    // to auto-dismiss on Android TV — confirmed live, one stayed on screen indefinitely.
    // Cancelling explicitly on a timer instead of trusting LENGTH_LONG's own timeout.
    private fun announceG733Battery(percent: Int, charging: Boolean) {
        mainHandler.post {
            g733BatteryToast?.cancel()
            val toast = Toast.makeText(
                this,
                "G733 battery: $percent%${if (charging) " (charging)" else ""}",
                Toast.LENGTH_LONG,
            )
            g733BatteryToast = toast
            toast.show()
            mainHandler.postDelayed({ toast.cancel() }, 3500)
        }
    }

    private fun sendG733BatteryQuery(connection: UsbDeviceConnection, hidIface: UsbInterface) {
        val out = ByteArray(20)
        out[0] = 0x11
        out[1] = 0xff.toByte()
        out[2] = 0x08
        out[3] = 0x0f
        val value = (0x02 shl 8) or 0x11 // report type OUTPUT (2), report id 0x11
        val n = connection.controlTransfer(0x21, 0x09, value, hidIface.id, out, out.size, 1000)
        log("G733: battery query sent, result=$n")
    }

    private fun sendG733LightsOff(connection: UsbDeviceConnection, hidIface: UsbInterface, side: Int) {
        val out = ByteArray(20)
        out[0] = 0x11
        out[1] = 0xff.toByte()
        out[2] = 0x04
        out[3] = 0x3e
        out[4] = side.toByte()
        out[5] = 0x00 // mode = off
        val value = (0x02 shl 8) or 0x11
        val n = connection.controlTransfer(0x21, 0x09, value, hidIface.id, out, out.size, 1000)
        log("G733: lights-off (side=$side) sent, result=$n")
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val line = "$msg\n"
        synchronized(logHistory) {
            logHistory.append(line)
            if (logHistory.length > 20000) logHistory.delete(0, logHistory.length - 20000)
        }
        logListener?.invoke(line)
    }

    override fun onDestroy() {
        stopGipSession()
        stopG733Session()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(usbAttachReceiver) }
        runCatching { unregisterReceiver(usbDetachReceiver) }
        if (BuildConfig.DEBUG) unregisterTestReceivers()
        runCatching { injector?.stopRumble() }
        runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key)
    }
}
