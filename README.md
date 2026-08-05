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
- Xbox/Guide button: short press → Home, hold ~500ms → Settings (opened via a real `am
  start` call from the Shizuku shell-UID process — a plain `Context.startActivity()` from
  the app itself gets silently blocked by Android's background-activity-launch restriction)
- **Real rumble/force-feedback support**, including in emulators (e.g. RetroArch) — a real
  Linux `uinput` virtual gamepad is created from the Shizuku shell-UID process (buttons,
  axes, and force-feedback all through one real Android `InputDevice`, not synthetic
  injection), so anything that calls `InputDevice.getVibrator()` — including RetroArch's
  own Android rumble path — routes through to the real controller motors over the existing
  GIP connection. Configurable in-app: on/off toggle + strength (10% steps) under
  **Configure Devices**. The two GIP trigger motors (impulse triggers) aren't reachable this
  way since the standard Linux `FF_RUMBLE` effect only carries two channels (main
  strong/weak) — real games can't address them, only this app's own manual test can
- System-wide input injection via a real uinput device (works in any app, not just this
  one's own UI) — see "Rumble/input architecture" below for why this replaced the earlier
  `InputManager.injectInputEvent`-based approach
- Runs as a foreground service — survives being backgrounded, and auto-reconnects on
  Shizuku restart
- G733 Lightspeed dongle: turns off headband + earcup RGB lighting on connect AND on every
  power-switch toggle (the dongle forgets the lights-off state on every power cycle, not
  just a USB unplug — protocol reverse-engineered by
  [YulCmr/G733_windows_app](https://github.com/YulCmr/G733_windows_app))
- G733 battery percentage: toggling the headset's power switch shows a toast with current
  battery % (and charging state) — same wire protocol as G HUB's own display, triggered
  off a link-event report captured live rather than continuous polling

## What doesn't work

- The 8BitDo-proprietary **Star** button produces zero USB packets over the wired
  connection — appears to be firmware-local only, not exposed over USB at all.
- The G733's USB permission dialog **cannot be made persistent** across a full device
  reboot. Android deliberately never persists USB permission grants for devices that
  report audio-capture capability (this headset has a mic) — confirmed by inspecting
  `adb shell dumpsys usb`'s live permission store, which only ever retains the grant for
  the non-audio 8BitDo controller. This is a privacy protection, not a bug, and there's no
  legitimate way around it. One tap after a reboot, and it's done until the next reboot.
- Rarely, unplugging and replugging a device a **second** time within the same app session
  (without the app being restarted in between) can leave the permission request stuck with
  no dialog ever shown. Looks like an Android-level throttle on repeated permission
  prompts from the same process, not something fixable cleanly from app code. Workaround:
  force-stop the app and reopen it (Android TV Settings → Apps → GIP Bridge → Force stop).
  A first-time attach in a fresh app launch always works.

## Rumble/input architecture

Buttons, sticks, and rumble all go through one real Linux `uinput` virtual gamepad, created
by the Shizuku shell-UID process (`GamepadInjectorService`, native code in
`app/src/main/cpp/uinput_gamepad.c`) — not through `InputManager.injectInputEvent`. That
earlier synthetic-injection approach worked fine for buttons/sticks but had no path to
rumble at all: apps that check which real `InputDevice` a button event came from (RetroArch
included) see synthetic injected events as device id `-1`, a sentinel most rumble code paths
explicitly skip. A real uinput device gives buttons/axes/FF a real device id everything can
see, at the cost of needing correct raw evdev button/axis codes instead of Android's
higher-level `KeyEvent`/`MotionEvent` constants.

GIP rumble packets (`buildRumblePayload` in `GipProtocol.kt`) are built from `xone`'s real
driver struct (`struct gip_gamepad_pkt_rumble`, `driver/gamepad.c`) and sent over the
existing GIP USB connection whenever the uinput device receives a real Linux force-feedback
upload/play event, polled off the same fd the device was created on
(`nativePollFF` in `uinput_gamepad.c`).

**Known Android-TV-specific quirk worth knowing if you fork this for other hardware:** this
device's vendor/product ID (`0x045e`/`0x02fd`, chosen to read as a real Xbox controller to
apps) happened to match a genuine Xbox `.kl` (key layout) file already on this specific
Shield unit — confirmed by reading `/system/usr/keylayout/Vendor_045e_Product_02fd.kl`
directly off the device. That file maps the View/Select button to raw `KEY_BACK` (158), not
the generic joystick `BTN_SELECT` (0x13a) code — sending the "obvious" code silently
produced a button press Android had no rule for. If buttons don't register correctly on
different hardware, check whether a matching `.kl` file exists on that device and what it
actually expects.

### Troubleshooting: rumble works, then silently stops

Check this app's own **Configure Devices** screen first — the rumble on/off toggle there is
a real, easy-to-forget-about switch, and was the actual cause the one time this exact
symptom came up during development (spent a while chasing RetroArch/uinput theories before
finding the toggle was just off). `sendRumble()` logs `"rumble disabled in settings,
skipping"` on every attempt when this is the cause — check logcat for that line before
anything else.

Two more settings that live in **RetroArch's own config**, not this app, can each
independently kill rumble with zero visible error — if RetroArch is ever reinstalled, has
its data wiped, or a config gets reset, check these next:

- **`enable_device_vibration` in `retroarch.cfg`** (`Android/data/com.retroarch.aarch64/files/retroarch.cfg`)
  — if `"true"`, RetroArch routes rumble to a whole-device vibrator sentinel (`id = -1`)
  meant for phones/tablets, which doesn't exist on a set-top box. Rumble calls silently
  no-op instead of reaching any real controller. Should be `"false"` (or absent, since that's
  also the effective default). Seen live: this key isn't exposed in any RetroArch menu —
  confirmed by grepping RetroArch's own menu string tables and finding no label for it — so
  if it's on, something set it programmatically (possibly RetroArch's own first-time device
  detection), not a setting you toggled on purpose.
- **The emulator core's own rumble/vibration option** — e.g. for PS1 via SwanStation,
  `swanstation_Controller_EnableRumble` in
  `RetroArch/config/SwanStation/SwanStation.opt`. This is a genuine, separate on/off switch
  per core for whether it emulates controller rumble at all, independent of everything else
  being correctly wired up. Different cores will have their own equivalent option under
  their own `.opt` file if a game doesn't rumble even though another game/core does.

## Requirements

- An Android TV device with a USB-A port (built and tested on an NVIDIA Shield TV Pro,
  Android 11)
- A PC with [`adb`](https://developer.android.com/tools/releases/platform-tools)
  (Android SDK Platform Tools), on the same network as the device, or a USB-C/micro-USB
  cable to it
- **Important:** `device_filter.xml` is hardcoded to this project's exact hardware — an
  8BitDo Ultimate Wired Controller for Xbox (VID `11720`/`0x2DC8`, PID `8213`/`0x2015`)
  and a Logitech G733 Lightspeed dongle (VID `1133`/`0x046D`, PID `2741`/`0x0AB5`). If you
  have the identical hardware this works unmodified; for anything else see
  "Customizing for other devices" below before you start.

## Setup, start to finish (factory-fresh device)

### 1. Enable Developer Options and USB debugging

On the Android TV device: **Settings → Device Preferences → About**, then click/select
**Build** repeatedly (7 times) until it says "You are now a developer." Back out to
**Settings → Device Preferences → Developer options**, and turn on **USB debugging**
(sometimes labeled **Network debugging** — it's the same fixed adb-over-tcpip toggle on
some Android TV builds, port 5555).

### 2. Connect adb from your PC

```
adb connect <device-ip>:5555
```
(Find the IP under Settings → Network & Internet.) Accept the "Allow debugging" prompt
that appears on the TV. `adb devices` should now list it.

### 3. Install Shizuku

Shizuku is a separate app this project depends on — download it from its
[official GitHub releases](https://github.com/RikkaApps/Shizuku/releases) (not the Play
Store version, which is deprecated) and sideload it:
```
adb install Shizuku-<version>.apk
```
Open it once on the TV so it can initialize.

### 4. Start Shizuku

Non-rooted Shizuku doesn't survive a reboot and has to be started this way every time:

**From a PC:** open the Shizuku app on the TV, go to "Start by connecting to a computer" →
"View command" — it shows the exact `adb shell <path>/libshizuku.so` command for your
specific install (the path includes a version-specific hash, so there's no fixed command
to copy-paste; grab it fresh from the app). Run that command from your PC.

**Fully on-device, no PC ever again:** if you install [Termux](https://termux.dev) +
[Termux:Boot](https://github.com/termux/termux-boot) on the TV, `scripts/shizuku_boot.sh`
resolves Shizuku's install path fresh each run and restarts it — copy it to
`~/.termux/boot/start-shizuku` inside Termux (`chmod +x` it) and it fires automatically on
every boot from then on. See `scripts/start_shizuku.ps1` for the PC-side equivalent if you
don't want the Termux route.

### 5. Install GIP Bridge

Download the latest APK from [Releases](../../releases) and sideload it the same way:
```
adb install gip-bridge-latest.apk
```

### 6. Plug in your controller (and headset dongle, if using that part)

Launch GIP Bridge once. It'll request a USB permission dialog for each connected device —
accept it. From then on it auto-launches whenever the controller is plugged in or the
device boots, binds to Shizuku automatically, and needs no further interaction — except
the G733's permission dialog, which Android requires re-accepting after every reboot (see
"What doesn't work" above for why).

## Using different hardware

The app ships pre-configured for an 8BitDo Ultimate Wired Controller for Xbox and a
Logitech G733 dongle, but neither is hardcoded — open the app and tap **Configure
Devices** to see every currently-attached USB device (name + vendor/product ID) and assign
one as "Controller" and/or one as "Headset". No rebuild, no file editing. Takes effect on
the next reconnect of that device (or after a reboot).

Two caveats this can't paper over:
- The GIP protocol layer (`GipProtocol.kt`) only speaks GIP — it'll work for any other
  Xbox-licensed wired controller, but not for a generic HID gamepad. The two firmware
  quirks documented in `GipBridgeService.kt` (interface 1 re-assert, explicit POWER=ON)
  may or may not be needed for other controllers.
- The lighting-off protocol (`turnOffG733Lights` in `GipBridgeService.kt`) is G733-specific
  wire format, not a generic Logitech/Lightspeed command — picking a different headset as
  "Headset" will just fail silently (harmless, but won't do anything).

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


## Support

If this saved you time or you just want to say thanks:

**Cash App:** [$CVanZetta](https://cash.app/$CVanZetta)
