#!/data/data/com.termux/files/usr/bin/bash
# Restarts Shizuku on-device, no PC needed. Requires Network debugging ON
# (Developer options > Network debugging) — it's a fixed adb-tcpip toggle
# on this Shield build, port 5555, not the phone-style pairing flow.
set -e

if ! command -v adb >/dev/null 2>&1; then
    echo "Installing android-tools (one-time)..."
    pkg install -y android-tools
fi

adb connect 127.0.0.1:5555
# Pin the target explicitly: this Shield's adb enumerates the loopback self-connection
# under two names at once (127.0.0.1:5555 AND a phantom emulator-5554 local-transport
# alias) which makes bare `adb shell` fail with "more than one device/emulator".
export ANDROID_SERIAL=127.0.0.1:5555

APP_DIR=$(adb shell pm path moe.shizuku.privileged.api | sed -E 's#^package:(.+)/base\.apk#\1#' | tr -d '\r')
if [ -z "$APP_DIR" ]; then
    echo "ERROR: could not resolve Shizuku's install path. Is it still installed?"
    exit 1
fi

STARTER="$APP_DIR/lib/arm64/libshizuku.so"
echo "Starting Shizuku via $STARTER ..."
adb shell "$STARTER"

sleep 1
echo ""
echo "Verifying:"
adb shell ps -A | grep shizuku_server || echo "NOT running — check output above."
