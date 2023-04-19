package it.m2app.screamreceiver

import android.content.Context
import android.content.SharedPreferences
import com.frybits.harmony.getHarmonySharedPreferences
import hu.autsoft.krate.Krate
import hu.autsoft.krate.booleanPref
import hu.autsoft.krate.default.withDefault
import hu.autsoft.krate.intPref

class ScreamSettings(context: Context) : Krate {
    override val sharedPreferences: SharedPreferences = context.getHarmonySharedPreferences("SCREAM")

    var serviceStarted by booleanPref().withDefault(false)
    var channels by intPref().withDefault(0)
    var sampleRate by intPref().withDefault(0)
    var sampleSize by intPref().withDefault(0)
}
