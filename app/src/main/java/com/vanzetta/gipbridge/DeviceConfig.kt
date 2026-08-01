package com.vanzetta.gipbridge

import android.content.Context

private const val PREFS_NAME = "gip_bridge_config"
private const val KEY_CONTROLLER_VID = "controller_vid"
private const val KEY_CONTROLLER_PID = "controller_pid"
private const val KEY_HEADSET_VID = "headset_vid"
private const val KEY_HEADSET_PID = "headset_pid"
private const val KEY_RUMBLE_ENABLED = "rumble_enabled"
private const val KEY_RUMBLE_STRENGTH = "rumble_strength"

// Defaults match the hardware this project was built and tested against — an 8BitDo
// Ultimate Wired Controller for Xbox and a Logitech G733 Lightspeed dongle. Override via
// SettingsActivity for different hardware, no rebuild needed.
private const val DEFAULT_CONTROLLER_VID = 11720
private const val DEFAULT_CONTROLLER_PID = 8213
private const val DEFAULT_HEADSET_VID = 1133
private const val DEFAULT_HEADSET_PID = 2741

object DeviceConfig {
    fun controllerVid(ctx: Context) = prefs(ctx).getInt(KEY_CONTROLLER_VID, DEFAULT_CONTROLLER_VID)
    fun controllerPid(ctx: Context) = prefs(ctx).getInt(KEY_CONTROLLER_PID, DEFAULT_CONTROLLER_PID)
    fun headsetVid(ctx: Context) = prefs(ctx).getInt(KEY_HEADSET_VID, DEFAULT_HEADSET_VID)
    fun headsetPid(ctx: Context) = prefs(ctx).getInt(KEY_HEADSET_PID, DEFAULT_HEADSET_PID)

    fun setController(ctx: Context, vid: Int, pid: Int) {
        prefs(ctx).edit().putInt(KEY_CONTROLLER_VID, vid).putInt(KEY_CONTROLLER_PID, pid).apply()
    }

    fun setHeadset(ctx: Context, vid: Int, pid: Int) {
        prefs(ctx).edit().putInt(KEY_HEADSET_VID, vid).putInt(KEY_HEADSET_PID, pid).apply()
    }

    fun rumbleEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_RUMBLE_ENABLED, true)
    fun setRumbleEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RUMBLE_ENABLED, enabled).apply()
    }

    /** 0-100, applied as a multiplier on top of whatever strength the game/emulator requested. */
    fun rumbleStrength(ctx: Context) = prefs(ctx).getInt(KEY_RUMBLE_STRENGTH, 100)
    fun setRumbleStrength(ctx: Context, percent: Int) {
        prefs(ctx).edit().putInt(KEY_RUMBLE_STRENGTH, percent.coerceIn(0, 100)).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
