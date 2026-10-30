package com.pandyabhavik.pedometer.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pandyabhavik.pedometer.work.Schedules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The 15-minute step read survives a reboot or an app update without opening the app. Before the phone
 * turns off, one last read saves the steps since the previous one: the step chip starts again from zero
 * at boot, so anything not read by then would be lost.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Schedules.scheduleCounting(context)
                read(context, timeoutMillis = 9_000)
            }
            Intent.ACTION_SHUTDOWN -> read(context, timeoutMillis = 4_000)
        }
    }

    private fun read(context: Context, timeoutMillis: Long) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(timeoutMillis) { StepReader.readNow(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
