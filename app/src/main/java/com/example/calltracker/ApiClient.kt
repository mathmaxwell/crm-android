package com.example.calltracker

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Posts call lifecycle events to the backend.
 *
 * Endpoints:
 *   POST {baseUrl}/api/calls/event       — ringing | answered | ended (audio multipart on ended)
 *   PATCH {baseUrl}/api/calls/{id}/caller — patches caller phone number after-the-fact
 */
object ApiClient {
    private const val TAG = "ApiClient"
    private const val NGROK_HEADER = "ngrok-skip-browser-warning"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // In-memory queue of "ended" events that the backend rejected or that
    // failed to deliver due to network issues. We keep retrying so a stuck
    // "talking" state on the dashboard eventually clears once the phone is
    // back online. Bounded to avoid unbounded growth if the backend is gone
    // for hours.
    private const val ENDED_QUEUE_MAX = 32
    private val endedQueue = ArrayDeque<EndedEvent>()
    private val endedFlushScheduled = AtomicBoolean(false)

    private data class EndedEvent(
        val callId: String,
        val atMillis: Long,
        val durationSec: Long,
        val status: String,
        val audioSource: String,
        var nextDelayMs: Long,
    )

    fun baseUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        // Fall back to the hard-coded DEFAULT_URL if the user never tapped
        // "Save" on first launch — otherwise every event would be silently
        // dropped (the EditText shows the default but prefs are empty).
        return prefs.getString(MainActivity.KEY_URL, MainActivity.DEFAULT_URL)
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    }

    fun sendRinging(context: Context, callId: String, caller: String, atMillis: Long) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("event", "ringing")
            .addFormDataPart("call_id", callId)
            .addFormDataPart("caller", caller)
            .addFormDataPart("at", isoFormatter.format(Date(atMillis)))
            .build()
        post(context, "/api/calls/event", body, "ringing $caller")
    }

    fun sendAnswered(context: Context, callId: String, atMillis: Long) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("event", "answered")
            .addFormDataPart("call_id", callId)
            .addFormDataPart("at", isoFormatter.format(Date(atMillis)))
            .build()
        post(context, "/api/calls/event", body, "answered")
    }

    /** Metadata-only "ended" event. Flips call status so the dashboard widget
     *  exits the talking state immediately. Audio is attached separately via
     *  [uploadAudio] once Mi Dialer's recorder finalises its mp3.
     *
     *  Losing an "ended" event leaves the call stuck in "talking" forever on
     *  the dashboard, so this path goes through a retry queue: if the POST
     *  fails (network down, ngrok hiccup, backend restarting) we re-attempt
     *  with exponential backoff until it lands. */
    fun sendEnded(
        context: Context,
        callId: String,
        atMillis: Long,
        durationSec: Long,
        status: String,
        audioSource: String = "none",
    ) {
        enqueueEnded(
            context,
            EndedEvent(callId, atMillis, durationSec, status, audioSource, nextDelayMs = 0L),
        )
    }

    private fun enqueueEnded(context: Context, ev: EndedEvent) {
        val appCtx = context.applicationContext
        synchronized(endedQueue) {
            // Replace any prior event for the same call_id so we don't post
            // stale duration/status if the call is re-flushed by the reconciler.
            val it = endedQueue.iterator()
            while (it.hasNext()) {
                if (it.next().callId == ev.callId) it.remove()
            }
            while (endedQueue.size >= ENDED_QUEUE_MAX) endedQueue.pollFirst()
            endedQueue.addLast(ev)
        }
        scheduleEndedFlush(appCtx, ev.nextDelayMs)
    }

    private fun scheduleEndedFlush(context: Context, delayMs: Long) {
        if (!endedFlushScheduled.compareAndSet(false, true)) return
        scheduler.schedule({
            endedFlushScheduled.set(false)
            flushEndedQueue(context)
        }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun flushEndedQueue(context: Context) {
        val snapshot: List<EndedEvent>
        synchronized(endedQueue) { snapshot = endedQueue.toList(); endedQueue.clear() }
        if (snapshot.isEmpty()) return

        val baseUrl = baseUrl(context) ?: run {
            // Backend URL not configured — push everything back and retry later.
            synchronized(endedQueue) { snapshot.forEach { endedQueue.addLast(it) } }
            scheduleEndedFlush(context, 5_000L)
            return
        }

        var anyFailed = false
        var nextDelay = 1_000L
        for (ev in snapshot) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("event", "ended")
                .addFormDataPart("call_id", ev.callId)
                .addFormDataPart("at", isoFormatter.format(Date(ev.atMillis)))
                .addFormDataPart("duration_sec", ev.durationSec.toString())
                .addFormDataPart("status", ev.status)
                .addFormDataPart("audio_source", ev.audioSource)
                .build()
            val url = "$baseUrl/api/calls/event"
            val req = Request.Builder()
                .url(url)
                .header(NGROK_HEADER, "true")
                .post(body)
                .build()
            val ok = try {
                client.newCall(req).execute().use { resp ->
                    Log.d(TAG, "POST $url [ended ${ev.callId.take(8)} status=${ev.status}] → ${resp.code}")
                    // 404 means the backend never saw "ringing"/"answered" for
                    // this call — retrying won't help, drop it.
                    resp.isSuccessful || resp.code == 404
                }
            } catch (e: Exception) {
                Log.w(TAG, "POST $url [ended ${ev.callId.take(8)}] failed: ${e.message}")
                false
            }
            if (!ok) {
                anyFailed = true
                val nextEv = ev.copy(nextDelayMs = (ev.nextDelayMs * 2).coerceAtLeast(2_000L).coerceAtMost(60_000L))
                synchronized(endedQueue) { endedQueue.addLast(nextEv) }
                nextDelay = nextEv.nextDelayMs.coerceAtLeast(nextDelay)
            }
        }
        if (anyFailed) scheduleEndedFlush(context, nextDelay)
    }

    /** Attaches a recording to an already-ended call via a dedicated endpoint
     *  so the call status can flip independently of (slow) audio upload. */
    fun uploadAudio(
        context: Context,
        callId: String,
        audioFile: File,
        audioSource: String = "MIUI_REC",
    ) {
        if (!audioFile.exists() || audioFile.length() <= 44) {
            Log.w(TAG, "uploadAudio: file missing or empty: ${audioFile.path}"); return
        }
        val mime = if (audioFile.name.endsWith(".wav", true)) "audio/wav" else "audio/mpeg"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio_source", audioSource)
            .addFormDataPart("audio", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
            .build()
        post(context, "/api/calls/$callId/audio", body,
            "audio src=$audioSource size=${audioFile.length()}b")
    }

    /** Updates the caller phone number on an existing call (when initially "unknown"). */
    fun patchCaller(context: Context, callId: String, caller: String) {
        val baseUrl = baseUrl(context) ?: return
        executor.submit {
            val url = "$baseUrl/api/calls/$callId/caller"
            val body = """{"caller":${JsonString.escape(caller)}}"""
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .header(NGROK_HEADER, "true")
                .patch(body)
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    Log.d(TAG, "PATCH $url → ${resp.code} caller=$caller")
                }
            } catch (e: Exception) {
                Log.e(TAG, "PATCH $url failed", e)
            }
        }
    }

    private fun post(context: Context, path: String, body: RequestBody, label: String) {
        val baseUrl = baseUrl(context) ?: run {
            Log.w(TAG, "Backend URL not set — skipping ($label)"); return
        }
        executor.submit {
            val url = "$baseUrl$path"
            // Short in-task retry: handles ngrok hiccups / Wi-Fi handoffs that
            // would otherwise drop the event silently. Multipart bodies aren't
            // re-readable in OkHttp, so build a fresh Request each attempt.
            val maxAttempts = 4
            var delayMs = 500L
            for (attempt in 1..maxAttempts) {
                val request = Request.Builder()
                    .url(url)
                    .header(NGROK_HEADER, "true")
                    .post(body)
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        Log.d(TAG, "POST $url [$label] → ${response.code} (try $attempt)")
                        if (response.isSuccessful || response.code == 404) return@submit
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "POST $url [$label] failed (try $attempt): ${e.message}")
                }
                if (attempt < maxAttempts) {
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@submit }
                    delayMs = (delayMs * 2).coerceAtMost(8_000L)
                }
            }
            Log.e(TAG, "POST $url [$label] gave up after $maxAttempts attempts")
        }
    }
}

private object JsonString {
    fun escape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
