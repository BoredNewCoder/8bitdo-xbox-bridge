# GIP Bridge

Makes an **8BitDo Ultimate Wired Controller for Xbox** work as a real system-wide gamepad
on Android TV / Google TV devices (built and tested on an NVIDIA Shield TV Pro) — despite
8BitDo's own FAQ saying this controller isn't Android-compatible.

Also includes an optional bonus: turns off the RGB lighting on a **Logitech G733**
wireless headset's Lightspeed USB dongle, no G HUB required.

## Why this exists

This controller is licensed as an official Xbox accessory, which means it speaks
Microsoft's GIP (Game Input Protocol) over USB rather than showing up as a standard HID
gamepad. Android has no built-in GIP support, so the OS can't read it at all — confirmed
via `adb shell dumpsys usb`, which reports the device as USB class 255 (vendor-specific),
not a HID gamepad class.

GIP itself has already been fully reverse-engineered and open-sourced by the
[`xone`](https://github.com/medusalix/xone) Linux driver project. This app reimplements
that same wire protocol as an Android USB Host API client, then uses
[Shizuku](https://shizuku.rikka.app/) to get real system-wide input injection rights
(the same ones `adb shell input` has) without root.

## What works

- Full button/stick/trigger mapping — A/B/X/Y, bumpers, triggers, both analog sticks, D-pad
- Xbox/Guide button: short press → Home, hold ~500ms → Settings
- System-wide input injection via Shizuku (works in any app, not just this one's own UI)
- Runs as a foreground service — survives being backgrounded, and auto-reconnects on
  Shizuku restart
- G733 Lightspeed dongle: turns off headband + earcup RGB lighting on connect (protocol
  reverse-engineered by [YulCmr/G733_windows_app](https://github.com/YulCmr/G733_windows_app))

## What doesn't work

- The 8BitDo-proprietary **Star** button produces zero USB packets over the wired
  connection — appears to be firmware-local only, not exposed over USB at all.
- The G733's USB permission dialog **cannot be made persistent** across a full device
  reboot. Android deliberately never persists USB permission grants for devices that
  report audio-capture capability (this headset has a mic) — confirmed by inspecting
  `adb shell dumpsys usb`'s live permission store, which only ever retains the grant for
  the non-audio 8BitDo controller. This is a privacy protection, not a bug, and there's no
  legitimate way around it. One tap after a reboot, and it's done until the next reboot.

## Requirements

- An Android TV device with a USB-A port and Developer Options enabled (USB debugging /
  network debugging)
- [Shizuku](https://shizuku.rikka.app/) installed from its official source
- A PC with `adb`, for the one-time Shizuku start (or see `scripts/` for a fully on-device
  alternative via Termux)

## Install

1. Download the latest APK from [Releases](../../releases)
2. Sideload it: `adb install app-debug.apk`, or copy it to the device and open it with a
   file manager (allow installs from unknown sources when prompted)
3. Launch the app once — it'll request the USB permission dialog(s) for whatever's plugged in
4. Start Shizuku (see below) — the app will bind to it automatically once it's running

### Starting Shizuku

Non-rooted Shizuku doesn't survive a device reboot and has to be restarted each time. Two ways:

**From a PC**, once per reboot: open the Shizuku app, go to "Start by connecting to a
computer" → "View command" — it shows the exact `adb shell <path>/libshizuku.so` command
for your specific install (the path includes a version-specific hash, so there's no fixed
command to copy-paste; grab it fresh from the app each time you set up a new device).

**Fully on-device** (no PC, ever) — if you have [Termux](https://termux.dev) +
[Termux:Boot](https://github.com/termux/termux-boot) installed, `scripts/shizuku_boot.sh`
resolves Shizuku's install path fresh each run and restarts it. Copy it to
`~/.termux/boot/start-shizuku` inside Termux and it'll fire automatically on every boot.
See `scripts/start_shizuku.ps1` for the PC-side equivalent.

## Customizing for other devices

Vendor/product IDs are hardcoded in `app/src/main/res/xml/device_filter.xml` and
`MainActivity`/`GipBridgeService`'s `VENDOR_ID_*`/`PRODUCT_ID_*` constants. Find yours via
`adb shell dumpsys usb` with the device plugged in, and swap the values. The GIP protocol
layer (`GipProtocol.kt`) should work unmodified for any Xbox-licensed wired controller
speaking the same protocol — the two firmware quirks documented in `GipBridgeService.kt`
(interface 1 re-assert, explicit POWER=ON) may or may not be needed for other controllers.

## Building from source

Standard Android Gradle project. `./gradlew assembleDebug`, output lands in
`app/build/outputs/apk/debug/`.

## Credits

- [medusalix/xone](https://github.com/medusalix/xone) (GPL-2.0-or-later) — the actual GIP
  protocol reverse-engineering this is built on
- [YulCmr/G733_windows_app](https://github.com/YulCmr/G733_windows_app) — G733 lighting
  control protocol
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) — the injection mechanism

## License

MIT for the code in this repo. The GIP protocol implementation was written from scratch
based on reading `xone`'s source, not copied from it — no GPL code is included here.
