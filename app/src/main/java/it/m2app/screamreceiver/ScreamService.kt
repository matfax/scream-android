package it.m2app.screamreceiver

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.*
import android.widget.Toast
import mu.KLogging
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.*
import kotlin.concurrent.thread
import kotlin.experimental.and

class ScreamService: Service() {
    companion object: KLogging() {
        private const val HEADER_SIZE = 5
        private const val MAX_SO_PACKETSIZE = 1152 + HEADER_SIZE
        private const val MULTICAST_HOST = "239.255.77.77"
        private const val MULTICAST_PORT = 4010
    }

    lateinit var settings: ScreamSettings
    private lateinit var track: AudioTrack
    private lateinit var socket: MulticastSocket
    private lateinit var group: InetAddress
    private var curChannelMapLSB = 0
    private var curChannelMapMSB = 0
    private var curChannelMap = 0
    private var curSampleRate = 0
    private lateinit var format: AudioFormat
    private var isServiceStarted = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.debug {"onStartCommand executed with startId: $startId"}
        if (intent != null) {
            val action = intent.action
            logger.debug { "using an intent with action $action" }
            when (action) {
                Actions.START.name -> startService()
                Actions.STOP.name -> stopService()
                else -> logger.error { "This should never happen. No action in the received intent" }
            }
        } else {
            logger.debug { "with a null intent. It has been probably restarted by the system." }
        }
        // by returning this we make sure the service is restarted if the system kills the service
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        settings = ScreamSettings(this)
        logger.info { "The scream receiver service has been created".uppercase(Locale.getDefault()) }
        val notification = createNotification()
        startForeground(1, notification)
    }

    private fun startService() {
        if (isServiceStarted) return
        logger.info { "Starting the foreground service task" }
        Toast.makeText(this, "Scream receiver starting its operation", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        settings.serviceStarted = true

        // we need this lock so our service gets not affected by Doze Mode
        val wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreamService::lock").apply {
                    acquire(7*24*60*60*1000L /*60 minutes*/)
                }
            }

        thread(start = true) {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val multicastLock = wifi.createMulticastLock("screamReceiver:MulticastLock")
            multicastLock.setReferenceCounted(true)
            multicastLock.acquire()
            socket = MulticastSocket(MULTICAST_PORT)
            group = InetAddress.getByName(MULTICAST_HOST)
            socket.joinGroup(group)
            val packet: DatagramPacket
            val buf = ByteArray(MAX_SO_PACKETSIZE)
            packet = DatagramPacket(buf, buf.size)
            while (isServiceStarted) {
                try {
                    readFromStream(packet)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to read audio package and play it" }
                    stopService()
                }
            }
            if (this@ScreamService::track.isInitialized) {
                track.stop()
                track.release()
            }
            try {
                socket.leaveGroup(group)
            } catch (e: IOException) {
                logger.warn(e) { "Failed to leave multicast group" }
            }
            try {
                socket.close()
            } catch (e: IOException) {
                logger.warn(e) { "Failed to close socket" }
            }
            wakeLock.release()
            multicastLock.release()
        }
    }

    private fun stopService() {
        logger.info { "Stopping the foreground service" }
        isServiceStarted = false
        settings.serviceStarted = false
        settings.sampleSize = 0
        settings.channels = 0
        settings.sampleRate = 0
        Toast.makeText(this, "Scream receiver service stopping", Toast.LENGTH_SHORT).show()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "SCREAM RECEIVER"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            notificationChannelId,
            "Scream Background Receiver",
            NotificationManager.IMPORTANCE_HIGH
        ).let {
            it.description = "To listen to scream multicasts with the receiver activity closed or in the background."
            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }

        val builder: Notification.Builder = Notification.Builder(
            this,
            notificationChannelId
        )

        return builder
            .setContentTitle("Scream Receiver")
            .setContentText("You are listening to scream multicast audio in the background.")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, ScreamService::class.java).also {
            it.setPackage(packageName)
        }
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT)
        applicationContext.getSystemService(Context.ALARM_SERVICE)
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
    }

    private fun readFromStream(packet: DatagramPacket) {
        socket.receive(packet)
        val data = packet.data
        if (data.size > HEADER_SIZE) {
            val d0: Int = (data[0] and 0xFF.toByte()).toInt()
            val d1: Int = (data[1] and 0xFF.toByte()).toInt()
            val d2: Int = (data[2] and 0xFF.toByte()).toInt()
            val d3: Int = (data[3] and 0xFF.toByte()).toInt()
            val d4: Int = (data[4] and 0xFF.toByte()).toInt()
            if (curSampleRate != d0 || settings.sampleSize != d1 || settings.channels != d2 || curChannelMapLSB != d3 || curChannelMapMSB != d4) {
                curSampleRate = d0
                settings.sampleSize = d1
                settings.channels = d2
                curChannelMapLSB = d3
                curChannelMapMSB = d4
                curChannelMap = curChannelMapMSB shl 8 or curChannelMapLSB
                settings.sampleRate = (if (curSampleRate >= 128) 44100 else 48000) * (curSampleRate % 128)
                if (settings.sampleSize != 16) {
                    logger.warn { "Only 16 bit audio streams are supported at the moment, not ${settings.sampleSize}." }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this,
                            "Only 16 bit audio streams are supported at the moment",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (this::track.isInitialized) {
                        track.stop()
                        track.release()
                    }
                    return
                }
                val formatBuilder = AudioFormat.Builder()
                format = when (settings.channels) {
                    1 -> {
                        formatBuilder
                            .setSampleRate(settings.sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    }
                    2 -> {
                        formatBuilder
                            .setSampleRate(settings.sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    }
                    else -> {
                        val channelMask =
                            curChannelMap shl 2 //windows and android constants for channels are in the same order, but android values are shifted by 2 positions
                        /*
                                int channelMask = 0;
                                // k is the key to map a windows SPEAKER_* position to a PA_CHANNEL_POSITION_*
                                // it goes from 0 (SPEAKER_FRONT_LEFT) up to 10 (SPEAKER_SIDE_RIGHT) following the order in ksmedia.h
                                // the SPEAKER_TOP_* values are not used
                                int k = -1;
                                for (int i = 0; i < curChannels; i++) {
                                    for (int j = k + 1; j <= 10; j++) {// check the channel map bit by bit from lsb to msb, starting from were we left on the previous step
                                        if(((curChannelMap >> j) & 0x01) !=0){// if the bit in j position is set then we have the key for this channel
                                            k = j;
                                            break;
                                        }
                                    }
                                    // map the key value to a pulseaudio channel position
                                    switch (k) {
                                        case 0:
                                            channelMask |= AudioFormat.CHANNEL_OUT_FRONT_LEFT;
                                            break;
                                        case 1:
                                            channelMask |= AudioFormat.CHANNEL_OUT_FRONT_RIGHT;
                                            break;
                                        case 2:
                                            channelMask |= AudioFormat.CHANNEL_OUT_FRONT_CENTER;
                                            break;
                                        case 3:
                                            channelMask |= AudioFormat.CHANNEL_OUT_LOW_FREQUENCY;
                                            break;
                                        case 4:
                                            channelMask |= AudioFormat.CHANNEL_OUT_BACK_LEFT;
                                            break;
                                        case 5:
                                            channelMask |= AudioFormat.CHANNEL_OUT_BACK_RIGHT;
                                            break;
                                        case 6:
                                            channelMask |= AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER;
                                            break;
                                        case 7:
                                            channelMask |= AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER;
                                            break;
                                        case 8:
                                            channelMask |= AudioFormat.CHANNEL_OUT_BACK_CENTER;
                                            break;
                                        case 9:
                                            channelMask |= AudioFormat.CHANNEL_OUT_SIDE_LEFT;
                                            break;
                                        case 10:
                                            channelMask |= AudioFormat.CHANNEL_OUT_SIDE_RIGHT;
                                            break;
                                        default:
                                            // center is a safe default, at least it's balanced. This shouldn't happen, but it's better to have a fallback
                                            channelMask |= AudioFormat.CHANNEL_OUT_FRONT_CENTER;
                                    }
                                }
                                */formatBuilder
                            .setSampleRate(settings.sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build()
                    }
                }
                val bufferSize =
                    AudioTrack.getMinBufferSize(format.sampleRate, format.channelMask, format.encoding)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                            .build()
                    )
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                track.play()
                logger.info { "Switched format to sample rate ${settings.sampleRate}, sample size ${settings.sampleSize} and ${settings.channels} channels." }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        this,
                        "Switched format to sample rate ${settings.sampleRate}, sample size ${settings.sampleSize} and ${settings.channels} channels",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        if (this::track.isInitialized) {
            track.write(data, 5, data.size - 5, AudioTrack.WRITE_NON_BLOCKING)
        }
    }
}