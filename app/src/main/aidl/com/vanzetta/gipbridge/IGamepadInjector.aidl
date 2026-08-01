package com.vanzetta.gipbridge;

import com.vanzetta.gipbridge.IRumbleCallback;

// Runs in a separate process spawned by Shizuku under the ADB shell UID, which already
// carries android.permission.INJECT_EVENTS natively (that's why `adb shell input ...`
// works without it ever being granted to a normal app) AND is the only UID that can open
// /dev/uinput on this device (confirmed live). Binding to this from the main app process
// gets us both that injection right and a real uinput-backed virtual gamepad.
interface IGamepadInjector {
    void injectKey(int keyCode, boolean down);
    void injectAxes(float x, float y, float z, float rz, float ltrigger, float rtrigger, float hatX, float hatY);
    void startRumble(IRumbleCallback callback);
    void stopRumble();
    void destroy();
}
