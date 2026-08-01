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
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

private const val TAG = "GipBridge"
private const val VENDOR_ID_8BITDO = 11720
private const val PRODUCT_ID_ULTIMATE_XBOX = 8213
private const val VENDOR_ID_LOGITECH = 1133
private const val PRODUCT_ID_G733 = 2741
private const val ACTION_USB_PERMISSION = "com.vanzetta.gipbridge.USB_PERMISSION"
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4242
private const val XBOX_LONG_PRESS_MS = 500L
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

    @Volatile private var injector: IGamepadInjector? = null
    private var lastButtons = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var xboxLongPressFired = false
    private val xboxLongPressRunnable = Runnable {
        xboxLongPressFired = true
        log("XBOX BUTTON: held -> opening Settings")
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { log("Settings launch failed: ${it.message}") }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, GamepadInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("injector").debuggable(false).version(1)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            injector = IGamepadInjector.Stub.asInterface(binder)
            log("Shizuku injector service connected.")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            injector = null
            log("Shizuku injector service disconnected.")
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted, binding injector service...")
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } else {
            log("Shizuku permission DENIED — system-wide injection unavailable.")
        }
    }

    private fun setupShizuku() {
        if (Shizuku.isPreV11()) { log("Shizuku pre-v11, unsupported."); return }
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
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
                    if (device.vendorId == VENDOR_ID_LOGITECH) turnOffG733Lights(device)
                    else startGipSession(device)
                } else {
                    log("USB permission DENIED")
                }
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            if (device != null && (device.vendorId == VENDOR_ID_8BITDO || device.vendorId == VENDOR_ID_LOGITECH)) {
                log("Attach event: ${device.deviceName} (vid=${device.vendorId} pid=${device.productId})")
                requestPermissionAndConnect(device)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        startInForeground()

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, attachFilter)
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky { setupShizuku() }

        log("GIP Bridge service started. Looking for 8BitDo device (vid=$VENDOR_ID_8BITDO)...")
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
        val targets = usbManager.deviceList.values.filter {
            it.vendorId == VENDOR_ID_8BITDO || it.vendorId == VENDOR_ID_LOGITECH
        }
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
            if (device.vendorId == VENDOR_ID_LOGITECH) turnOffG733Lights(device) else startGipSession(device)
            return
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
        running = true
        readerThread = thread(name = "gip-reader") { readLoop(connection, epIn, epOut) }
    }

    private fun readLoop(conn: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint) {
        val buf = ByteArray(256)
        var packetsSeen = 0
        var inputPacketsSeen = 0
        while (running) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 2000)
            if (n <= 0) continue // timeout or nothing yet — keep polling

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
                        log("INPUT: ${state.describe()}")
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
            state.stickLeftY / 32767f,
            state.stickRightX / 32767f,
            state.stickRightY / 32767f,
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
    private fun turnOffG733Lights(device: UsbDevice) {
        val hidIface: UsbInterface? = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        if (hidIface == null) { log("G733: HID interface not found"); return }

        val connection = usbManager.openDevice(device)
        if (connection == null) { log("G733: openDevice failed"); return }
        if (!connection.claimInterface(hidIface, true)) { log("G733: claimInterface failed"); return }

        val reportIdOutput = 0x11
        val hidSetReport = 0x09
        val hidReportTypeOutput = 0x02
        val controlRequestType = 0x21 // host-to-device | class | interface

        fun sendLightsOff(side: Int) {
            val out = ByteArray(20)
            out[0] = reportIdOutput.toByte()
            out[1] = 0xff.toByte()
            out[2] = 0x04
            out[3] = 0x3e
            out[4] = side.toByte()
            out[5] = 0x00 // mode = off
            val value = (hidReportTypeOutput shl 8) or reportIdOutput
            val n = connection.controlTransfer(controlRequestType, hidSetReport, value, hidIface.id, out, out.size, 1000)
            log("G733: lights-off (side=$side) sent, result=$n")
        }

        sendLightsOff(0x00) // bottom zone
        sendLightsOff(0x01) // top zone
        connection.releaseInterface(hidIface)
        connection.close()
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
        running = false
        readerThread?.join(500)
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(usbAttachReceiver) }
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
