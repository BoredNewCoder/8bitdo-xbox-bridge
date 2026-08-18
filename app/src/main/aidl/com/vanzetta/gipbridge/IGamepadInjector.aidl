package com.vanzetta.gipbridge;

import com.vanzetta.gipbridge.IRumbleCallback;

// Runs in a separate process spawned by Shizuku under the ADB shell UID, which already
// carries android.permission.INJECT_EVENTS natively (that's why `adb shell input ...`
// works without it ever being granted to a normal app) AND is the only UID that can open
// /dev/uinput on this device (confirmed live). Binding to this from the main app process
// gets us both that injection right and a real uinput-backed virtual gamepad.
interface IGamepadInjector {
    // playerIndex selects which uinput virtual gamepad (0 = player 1, 1 = player 2) a call
    // targets — added for 2-controller support. Each index gets its own uinput device (own
    // name, own FF effect table), opened lazily via openDevice() once GipBridgeService knows
    // a real controller is connected for that slot.
    boolean openDevice(int playerIndex, String name);
    void closeDevice(int playerIndex);
    void injectKey(int playerIndex, int keyCode, boolean down);
    void injectAxes(int playerIndex, float x, float y, float z, float rz, float ltrigger, float rtrigger, float hatX, float hatY);
    void startRumble(int playerIndex, IRumbleCallback callback);
    void stopRumble(int playerIndex);
    void openSettings();
    void destroy();
}
