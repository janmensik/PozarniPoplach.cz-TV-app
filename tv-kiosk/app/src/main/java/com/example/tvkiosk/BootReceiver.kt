package com.example.tvkiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the BOOT_COMPLETED broadcast and re-launches the kiosk.
 *
 * This means that after a power cycle (or forced reboot) the TV will
 * automatically return to the kiosk page without any manual intervention.
 *
 * Requires in the manifest:
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }
}
