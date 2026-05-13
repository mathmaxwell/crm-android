package com.example.calltracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that, for one call:
 *   • Captures audio from MediaRecorder.AudioSource.VOICE_CALL (root) or fallbacks.
 *   • Persists raw PCM to a WAV file in the app cache.
 *   • Pushes the same PCM chunks over a WebSocket so the dashboard can listen live.
 *   • On stop, posts the "ended" event with the WAV file attached.
 *
 * Started/stopped by [CallReceiver]:
 *   ACTION_START  + EXTRA_CALL_ID + EXTRA_BASE_URL
 *   ACTION_STOP   + EXTRA_AT_MILLIS + EXTRA_DURATION_SEC + EXTRA_STATUS
 */
class CallStreamService : Service() {

    companion object {
        private const val TAG = "CallStreamService"
        private const val NOTIF_ID = 4242
        private const val CHANNEL_ID = "call_stream"

        const val ACTION_START = "com.example.calltracker.action.START"
        const val ACTION_STOP = "com.example.calltracker.action.STOP"

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_AT_MILLIS = "extra_at"
        const val EXTRA_DURATION_SEC = "extra_duration"
        const val EXTRA_STATUS = "extra_status"

        const val SAMPLE_RATE = 8000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var ws: WebSocket? = null
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var callId: String? = null
    private var wavFile: File? = null
    private var wavRaf: RandomAccessFile? = null
    private var pcmBytesWritten: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_CALL_ID)
                val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
                if (id.isNullOrBlank()) {
                    Log.w(TAG, "ACTION_START missing call_id"); stopSelf(); return START_NOT_STICKY
                }
                if (running.compareAndSet(false, true)) {
                    callId = id
                    startRecording(id, baseUrl)
                }
            }
            ACTION_STOP -> {
                val atMs = intent.getLongExtra(EXTRA_AT_MILLIS, System.currentTimeMillis())
                val dur = intent.getLongExtra(EXTRA_DURATION_SEC, 0L)
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "answered"
                stopRecordingAndUpload(atMs, dur, status)
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action ${intent?.action}")
                if (!running.get()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running.get()) stopRecordingAndUpload(System.currentTimeMillis(), 0, "answered")
    }

    // ------------------------------------------------------------------ recording

    @SuppressLint("MissingPermission")
    private fun startRecording(callId: String, baseUrl: String?) {
        if (!baseUrl.isNullOrBlank()) openWebSocket(callId, baseUrl)

        val out = File(cacheDir, "call_${callId}.wav")
        try {
            wavFile = out
            wavRaf = RandomAccessFile(out, "rw").apply {
                setLength(0)
                writeWavHeader(this, SAMPLE_RATE, 1, 16, 0)
            }
            pcmBytesWritten = 0
        } catch (e: Exception) {
            Log.e(TAG, "WAV init failed", e); wavFile = null
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = (minBuf.coerceAtLeast(4096)) * 2

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        var rec: AudioRecord? = null
        for (src in sources) {
            try {
                val r = AudioRecord(src, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    Log.i(TAG, "AudioRecord opened with source=$src")
                    break
                } else r.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source=$src failed: ${e.message}")
            }
        }
        if (rec == null) {
            Log.e(TAG, "Could not open AudioRecord with any source"); return
        }
        recorder = rec

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::lock").apply {
            setReferenceCounted(false); acquire(30 * 60_000L)
        }

        rec.startRecording()
        captureThread = Thread({ captureLoop(rec, bufSize) }, "call-capture").apply { start() }
    }

    private fun captureLoop(rec: AudioRecord, bufSize: Int) {
        val buf = ByteArray(bufSize)
        try {
            while (running.get()) {
                val read = rec.read(buf, 0, buf.size)
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "AudioRecord.read returned $read")
                    continue
                }
                try {
                    wavRaf?.write(buf, 0, read)
                    pcmBytesWritten += read
                } catch (e: Exception) { Log.w(TAG, "WAV write failed", e) }

                ws?.let { w ->
                    try { w.send(buf.copyOf(read).toByteString(0, read)) }
                    catch (e: Exception) { Log.w(TAG, "WS send failed", e) }
                }
            }
        } finally {
            Log.d(TAG, "Capture loop exiting; bytes=$pcmBytesWritten")
        }
    }

    private fun stopRecordingAndUpload(atMs: Long, durationSec: Long, status: String) {
        if (!running.compareAndSet(true, false)) return

        try { captureThread?.join(1500) } catch (_: InterruptedException) {}
        captureThread = null

        try {
            recorder?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            }
        } catch (e: Exception) { Log.w(TAG, "recorder release", e) }
        recorder = null

        try {
            wavRaf?.use { raf ->
                val total = pcmBytesWritten + 36
                raf.seek(4);  raf.writeIntLE(total.toInt())
                raf.seek(40); raf.writeIntLE(pcmBytesWritten.toInt())
            }
        } catch (e: Exception) { Log.w(TAG, "WAV finalize", e) }
        wavRaf = null

        try { ws?.close(1000, "call ended") } catch (_: Exception) {}
        ws = null

        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null

        val finalFile = wavFile?.takeIf { it.exists() && it.length() > 44 }
        wavFile = null

        val cId = callId
        if (cId != null) {
            ApiClient.sendEnded(applicationContext, cId, atMs, durationSec, status, finalFile)
            Log.i(TAG, "Sent ended; file=${finalFile?.length() ?: 0}b")
        }
        callId = null
    }

    // ------------------------------------------------------------------ websocket

    private fun openWebSocket(callId: String, baseUrl: String) {
        val wsUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/ws/audio?role=streamer&call_id=$callId" +
                "&sample_rate=$SAMPLE_RATE&channels=1&format=pcm16"

        val request = Request.Builder()
            .url(wsUrl)
            .header("ngrok-skip-browser-warning", "true")
            .build()

        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS open → $wsUrl")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}"); ws = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $code $reason"); ws = null
            }
        })
    }

    // ------------------------------------------------------------------ foreground

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Call recording")
            .setContentText("Streaming audio…")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ------------------------------------------------------------------ WAV helpers

    private fun writeWavHeader(
        raf: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataLen: Int,
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        raf.writeBytes("RIFF")
        raf.writeIntLE(36 + dataLen)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)
        raf.writeShortLE(1)
        raf.writeShortLE(channels)
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(blockAlign)
        raf.writeShortLE(bitsPerSample)
        raf.writeBytes("data")
        raf.writeIntLE(dataLen)
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(b)
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        write(b)
    }
}
