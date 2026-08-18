package com.vanzetta.gipbridge

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Lets someone with different hardware point this app at their own controller/headset
 * without touching source or rebuilding — pick from currently-attached USB devices,
 * assign a role, done. Takes effect on the next USB attach (replug or reboot).
 */
class SettingsActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var usbManager: UsbManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply { addView(container); isFocusable = false; isFocusableInTouchMode = false }
        setContentView(scroll)
        render()
    }

    private fun render() {
        container.removeAllViews()

        container.addView(TextView(this).apply {
            text = "Assign a role to a currently-attached USB device. Changes take effect " +
                "the next time that device is plugged in (or after a reboot)."
            textSize = 15f
            setPadding(0, 0, 0, 32)
        })

        container.addView(currentConfigView())
        container.addView(rumbleSection())

        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "\nNo USB devices currently attached. Plug in your controller or " +
                    "headset dongle, then reopen this screen."
                textSize = 13f
            })
            return
        }

        for (device in devices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 24, 0, 24)
            }
            row.addView(TextView(this).apply {
                text = "${device.productName ?: "Unknown device"}\nvid=${device.vendorId} pid=${device.productId}"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val controllerBtn = Button(this).apply {
                text = "Set as Controller 1"
                isFocusableInTouchMode = true
                setOnClickListener {
                    DeviceConfig.setController(this@SettingsActivity, device.vendorId, device.productId)
                    Toast.makeText(this@SettingsActivity, "Controller 1 set to ${device.productName}", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            row.addView(controllerBtn)
            // 2-player support: a second, independent controller slot — same VID/PID-match
            // role concept as Controller 1, just feeding player index 1 instead of 0. Both
            // controllers can be connected and bridged simultaneously once both slots are set.
            row.addView(Button(this).apply {
                text = "Set as Controller 2"
                isFocusableInTouchMode = true
                setOnClickListener {
                    DeviceConfig.setController2(this@SettingsActivity, device.vendorId, device.productId)
                    Toast.makeText(this@SettingsActivity, "Controller 2 set to ${device.productName}", Toast.LENGTH_SHORT).show()
                    render()
                }
            })
            row.addView(Button(this).apply {
                text = "Set as Headset"
                isFocusableInTouchMode = true
                setOnClickListener {
                    DeviceConfig.setHeadset(this@SettingsActivity, device.vendorId, device.productId)
                    Toast.makeText(this@SettingsActivity, "Headset set to ${device.productName}", Toast.LENGTH_SHORT).show()
                    render()
                }
            })
            container.addView(row)
        }
    }

    private fun rumbleSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }
        section.addView(TextView(this).apply {
            text = "Rumble (RetroArch/emulators)"
            textSize = 15f
        })

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusRow.addView(TextView(this).apply {
            text = "Enabled: ${DeviceConfig.rumbleEnabled(this@SettingsActivity)}  " +
                "Strength: ${DeviceConfig.rumbleStrength(this@SettingsActivity)}%"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        section.addView(statusRow)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        controlsRow.addView(Button(this).apply {
            text = if (DeviceConfig.rumbleEnabled(this@SettingsActivity)) "Turn Off" else "Turn On"
            isFocusableInTouchMode = true
            setOnClickListener {
                DeviceConfig.setRumbleEnabled(this@SettingsActivity, !DeviceConfig.rumbleEnabled(this@SettingsActivity))
                render()
            }
        })
        controlsRow.addView(Button(this).apply {
            text = "-10%"
            isFocusableInTouchMode = true
            setOnClickListener {
                DeviceConfig.setRumbleStrength(this@SettingsActivity, DeviceConfig.rumbleStrength(this@SettingsActivity) - 10)
                render()
            }
        })
        controlsRow.addView(Button(this).apply {
            text = "+10%"
            isFocusableInTouchMode = true
            setOnClickListener {
                DeviceConfig.setRumbleStrength(this@SettingsActivity, DeviceConfig.rumbleStrength(this@SettingsActivity) + 10)
                render()
            }
        })
        controlsRow.addView(Button(this).apply {
            text = "Test Rumble"
            isFocusableInTouchMode = true
            setOnClickListener {
                sendBroadcast(android.content.Intent("com.vanzetta.gipbridge.TEST_RUMBLE").setPackage(packageName))
                Toast.makeText(this@SettingsActivity, "Sent test rumble — should buzz for 1s", Toast.LENGTH_SHORT).show()
            }
        })
        section.addView(controlsRow)
        return section
    }

    private fun currentConfigView(): TextView = TextView(this).apply {
        textSize = 12f
        setPadding(0, 0, 0, 32)
        val c2 = if (DeviceConfig.controller2Configured(this@SettingsActivity))
            "vid=${DeviceConfig.controller2Vid(this@SettingsActivity)} pid=${DeviceConfig.controller2Pid(this@SettingsActivity)}"
        else "(not set)"
        text = "Current controller 1: vid=${DeviceConfig.controllerVid(this@SettingsActivity)} " +
            "pid=${DeviceConfig.controllerPid(this@SettingsActivity)}\n" +
            "Current controller 2: $c2\n" +
            "Current headset: vid=${DeviceConfig.headsetVid(this@SettingsActivity)} " +
            "pid=${DeviceConfig.headsetPid(this@SettingsActivity)}"
    }
}
