package com.vanzetta.gipbridge;

// Runs from the Shizuku shell-UID process's FF poll thread back into the main app process,
// which owns the real USB connection to the controller and is the only place that can
// actually send a GIP rumble packet over the wire.
oneway interface IRumbleCallback {
    void onRumble(int strongPercent, int weakPercent, int durationMs);
    void onRumbleStop();
}
