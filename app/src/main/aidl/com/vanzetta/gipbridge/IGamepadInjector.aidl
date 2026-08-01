package com.vanzetta.gipbridge;

// Runs in a separate process spawned by Shizuku under the ADB shell UID, which already
// carries android.permission.INJECT_EVENTS natively (that's why `adb shell input ...`
// works without it ever being granted to a normal app). Binding to this from the main
// app process gets us that same injection right without shelling out per event.
interface IGamepadInjector {
    void injectKey(int keyCode, boolean down);
    void injectAxes(float x, float y, float z, float rz, float ltrigger, float rtrigger, float hatX, float hatY);
    void destroy();
}
