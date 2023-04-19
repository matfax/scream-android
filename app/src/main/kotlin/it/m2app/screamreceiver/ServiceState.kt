package it.m2app.screamreceiver

import android.app.ActivityManager
import android.content.Context

@Suppress("DEPRECATION") // Deprecated for third party Services.
inline fun <reified T> Context.isServiceRunning() =
    (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(Integer.MAX_VALUE)
        .any { it.service.className == T::class.java.name }
