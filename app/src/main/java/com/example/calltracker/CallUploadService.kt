package com.example.calltracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Started by [CallReceiver] when a call ends. Looks for the .mp3 produced by
 * Mi Dialer's built-in call recorder (HyperOS writes one per call to
 * /sdcard/MIUI/sound_recorder/call_rec/), waits up to 30 s for it to be
 * finalised, then uploads it via [ApiClient.sendEnded].
 *
 * Falls back to a metadata-only "ended" event if no file appears in time —
 * usually because the user did not enable auto-record in the Dialer.
 *
 * Requires MANAGE_EXTERNAL_STORAGE; granted manually from MainActivity.
 */
class CallUploadService : Service() {

    companion object {
        private const val TAG = "CallUploadService"
        private const val NOTIF_ID = 4243
        private const val CHANNEL_ID = "call_upload"

        const val ACTION_UPLOAD = "com.example.calltracker.action.UPLOAD"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER = "extra_caller"
        const val EXTRA_CALL_START_MS = "extra_call_start_ms"
        const val EXTRA_CALL_END_MS = "extra_call_end_ms"

        private val REC_DIRS = listOf(
            File("/sdcard/MIUI/sound_recorder/call_rec"),
            File(Environment.getExternalStorageDirectory(), "MIUI/sound_recorder/call_rec"),
        )

        private const val WAIT_TOTAL_MS = 30_000L
        private const val POLL_INTERVAL_MS = 1_500L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (intent?.action != ACTION_UPLOAD) {
            Log.w(TAG, "Unknown action ${intent?.action}")
            stopSelf(startId); return START_NOT_STICKY
        }

        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        if (callId.isNullOrBlank()) {
            Log.w(TAG, "Missing call_id"); stopSelf(startId); return START_NOT_STICKY
        }
        val caller = intent.getStringExtra(EXTRA_CALLER) ?: "unknown"
        val callStartMs = intent.getLongExtra(EXTRA_CALL_START_MS, 0L)
        val callEndMs = intent.getLongExtra(EXTRA_CALL_END_MS, System.currentTimeMillis())

        thread(name = "call-upload", start = true, isDaemon = true) {
            try {
                val recording = waitForRecording(caller, callStartMs, callEndMs)
                if (recording != null) {
                    Log.i(TAG, "Uploading ${recording.name} (${recording.length()}b)")
                    ApiClient.uploadAudio(applicationContext, callId, recording, "MIUI_REC")
                } else {
                    Log.w(TAG, "No recording found in ${REC_DIRS.joinToString { it.path }}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload thread failed", e)
            } finally {
                stopSelfSafely(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfSafely(startId: Int) {
        val main = android.os.Handler(Looper.getMainLooper())
        main.post { stopSelf(startId) }
    }

    /** Polls the Mi recorder directory for a fresh .mp3 attributable to this
     *  call. The file's mtime must fall inside the call's lifetime window
     *  (with a small grace on both ends) and its name should contain the
     *  last 7 digits of the caller's number when available. */
    private fun waitForRecording(caller: String, callStartMs: Long, callEndMs: Long): File? {
        val tail = caller.filter { it.isDigit() }.takeLast(7)
        // Wait up to 30s for MIUI to flush, but never longer than the call
        // itself + 30s — otherwise a subsequent call's recording starts
        // appearing and we'd accidentally attach IT to this call.
        val deadline = System.currentTimeMillis() + WAIT_TOTAL_MS
        var lastSize = -1L
        var stableFile: File? = null

        while (System.currentTimeMillis() < deadline) {
            val candidate = scanDirs(tail, callStartMs, callEndMs)
            if (candidate != null) {
                val size = candidate.length()
                if (candidate == stableFile && size == lastSize && size > 0) {
                    return candidate
                }
                stableFile = candidate
                lastSize = size
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
        // Final attempt: return whatever was found even if size wasn't observed
        // as stable — the file may have been written in one shot.
        return stableFile?.takeIf { it.exists() && it.length() > 0 }
    }

    private fun scanDirs(callerTail: String, callStartMs: Long, callEndMs: Long): File? {
        // Window: [callStart - 2s, callEnd + 10s]. The floor avoids cross-call
        // contamination when MIUI didn't record a too-short call; the ceiling
        // avoids attaching a NEXT call's mp3 to THIS call (MIUI finalises the
        // file when its call ends, so files newer than callEnd+10s belong to a
        // later call).
        val floor = (callStartMs - 2_000L).coerceAtLeast(0L)
        val ceiling = callEndMs + 10_000L
        val candidates = mutableListOf<File>()
        for (dir in REC_DIRS) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { f ->
                f.isFile
                    && f.name.endsWith(".mp3", ignoreCase = true)
                    && f.lastModified() in floor..ceiling
            } ?: continue
            candidates += files
        }
        if (candidates.isEmpty()) return null

        val byCaller = if (callerTail.isNotEmpty()) {
            candidates.filter { it.name.contains(callerTail) }
        } else emptyList()

        return (byCaller.takeIf { it.isNotEmpty() } ?: candidates)
            .maxByOrNull { it.lastModified() }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Call upload", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading call recording")
            .setContentText("Waiting for recording to finalise…")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
