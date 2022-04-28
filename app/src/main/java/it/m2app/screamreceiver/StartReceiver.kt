package it.m2app.screamreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = ScreamSettings(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && settings.serviceStarted) {
            Intent(context, ScreamService::class.java).also {
                it.action = Actions.START.name
                context.startForegroundService(it)
            }
        }
    }
}