package it.m2app.screamreceiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.anastr.speedviewlib.PointerSpeedometer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KLogging

class MainActivity : AppCompatActivity() {
    companion object: KLogging()

    lateinit var settings: ScreamSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = ScreamSettings(this)

        title = "Scream Receiver"
        val startButton = findViewById<Button>(R.id.start_service)
        val stopButton = findViewById<Button>(R.id.stop_service)
        val sampleRateTextView = findViewById<PointerSpeedometer>(R.id.sample_rate)
        val sampleSizeTextView = findViewById<PointerSpeedometer>(R.id.sample_size)
        val channelsTextView = findViewById<PointerSpeedometer>(R.id.channels)

        startButton.setOnClickListener {
            logger.info { "Starting the Scream Receiver service" }
            actionOnService(Actions.START)
        }

        stopButton.setOnClickListener {
            logger.info { "Stopping the Scream Receiver service" }
            actionOnService(Actions.STOP)
        }

        updateState()

        lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                val startVisible = startButton.visibility == View.VISIBLE
                if (settings.serviceStarted && startVisible) {
                    startButton.visibility = View.GONE
                    stopButton.visibility = View.VISIBLE
                } else if (!settings.serviceStarted && !startVisible) {
                    startButton.visibility = View.VISIBLE
                    stopButton.visibility = View.GONE
                }
                val kHz = settings.sampleRate/1000
                if (!sampleRateTextView.currentSpeed.toString().startsWith(kHz.toString())) {
                    sampleRateTextView.speedTo(kHz.toFloat())
                }
                if (!sampleRateTextView.currentSpeed.toString().startsWith(settings.sampleSize.toString())) {
                    sampleSizeTextView.speedTo(settings.sampleSize.toFloat())
                }
                if (!sampleRateTextView.currentSpeed.toString().startsWith(settings.channels.toString())) {
                    channelsTextView.speedTo(settings.channels.toFloat())
                }
                delay(500)
            }
        }
    }

    private fun actionOnService(action: Actions) {
        if (settings.serviceStarted && action == Actions.START) return
        Intent(this, ScreamService::class.java).also {
            it.action = action.name
            startForegroundService(it)
        }
    }

    private fun updateState() {
        if (settings.serviceStarted && !isServiceRunning<ScreamService>()) {
            settings.serviceStarted = false
        } else if (!settings.serviceStarted && isServiceRunning<ScreamService>()) {
            settings.serviceStarted = true
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
    }

}