package it.m2app.screamreceiver;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends AppCompatActivity {
    private static final int HEADER_SIZE = 12;
    private static final int MAX_SO_PACKETSIZE = 320 + HEADER_SIZE;

    private boolean running = true;
    private AudioTrack track = null;

    private int curSampleSize = 16;
    private int curChannels = 2;

    private String infoMsg;

    private Runnable updateUI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final TextView intro = findViewById(R.id.intro);
        intro.setMovementMethod(LinkMovementMethod.getInstance());

        final TextView info = findViewById(R.id.info);

        final Handler h = new Handler(Looper.getMainLooper());
        updateUI = new Runnable() {
            @Override
            public void run() {
                info.setText(infoMsg);

                h.postDelayed(updateUI, 1000);
            }
        };
        h.postDelayed(updateUI, 1000);

        Thread producer = new Thread() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

                AudioFormat format;

                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiManager.MulticastLock multicastLock = wifi.createMulticastLock("screamReceiver:MulticastLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();

                MulticastSocket socket = null;
                InetAddress group = null;
                try {
                    infoMsg = "Awaiting for multicast packets.";

                    socket = new MulticastSocket(4010);
                    group = InetAddress.getByName("224.0.0.56");
                    socket.joinGroup(group);

                    DatagramPacket packet;

                    byte[] buf = new byte[MAX_SO_PACKETSIZE];
                    packet = new DatagramPacket(buf, buf.length);

                    while (running) {
                        socket.receive(packet);
                        byte[] data = packet.getData();

                        int sampleRate = 48000;

                        if (track == null) {// Android only support PCM 8, 16 or float. 24 and 32 bit integer are unsupported.
                            AudioFormat.Builder formatBuilder = new AudioFormat.Builder();

                            if (curChannels == 1) {
                                format = formatBuilder
                                        .setSampleRate(sampleRate)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .build();
                            } else if (curChannels == 2) {
                                format = formatBuilder
                                        .setSampleRate(sampleRate)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                        .build();
                            } else {
                                throw new IOException();
                            }
                            int bufferSize = AudioTrack.getMinBufferSize(format.getSampleRate(), format.getChannelMask(), format.getEncoding());
                            track = new AudioTrack(
                                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                                format, bufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                            track.play();

                            AudioManager aManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                            infoMsg = "Switched format to sample rate " + sampleRate + ", sample size " + curSampleSize + " and " + curChannels + " channels." + data.length + " opf:" + aManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) + " samr:" + aManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                            System.err.println(infoMsg);
                        }

                        short[] shorts = new short[(data.length - HEADER_SIZE) / 2];
                        ByteBuffer.wrap(data, HEADER_SIZE, data.length - HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(shorts);
                        track.write(shorts, 0, shorts.length);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (track != null) {
                    track.stop();
                    track.release();
                }
                if (socket != null) {
                    if (group != null) {
                        try {
                            socket.leaveGroup(group);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    socket.close();
                }

                multicastLock.release();
            }
        };
        producer.setPriority(Thread.MAX_PRIORITY);
        producer.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
    }
}
