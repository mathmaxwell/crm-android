package com.example.calltracker

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SMS events on their way to the backend: what the phone received and what
 * became of what it sent. POST {baseUrl}/api/sms/event, JSON.
 *
 * An incoming SMS must not be lost while Wi-Fi is down or the app is killed,
 * so events are kept in SharedPreferences until the backend answers: 2xx —
 * stored; 4xx — refused for good (dropped); anything else — retried with
 * backoff, in order, from the first one that failed.
 */
object SmsOutbox {
    private const val TAG = "SmsOutbox"
    private const val PREFS = "sms_outbox"
    private const val KEY = "events"
    private const val MAX_EVENTS = 500

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private val flushScheduled = AtomicBoolean(false)
    @Volatile private var backoffMs = 0L

    fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    /** One event per SMS and kind: a repeat replaces the one waiting. */
    private fun keyOf(e: JSONObject) =
        "${e.optString("event")}|${e.optString("sms_id")}|${e.optString("status")}"

    fun add(context: Context, event: JSONObject) {
        val app = context.applicationContext
        synchronized(lock) {
            val current = load(app)
            val key = keyOf(event)
            val next = JSONArray()
            for (i in 0 until current.length()) {
                val e = current.optJSONObject(i) ?: continue
                if (keyOf(e) != key) next.put(e)
            }
            next.put(event)
            while (next.length() > MAX_EVENTS) next.remove(0)
            save(app, next)
        }
        flushSoon(app, 0L)
    }

    /** Also called when the command socket reconnects: the network is back. */
    fun flushSoon(context: Context, delayMs: Long) {
        val app = context.applicationContext
        if (!flushScheduled.compareAndSet(false, true)) return
        executor.schedule({
            flushScheduled.set(false)
            try { flush(app) } catch (e: Exception) { Log.e(TAG, "flush failed", e) }
        }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    fun pending(context: Context): Int = synchronized(lock) { load(context.applicationContext).length() }

    private fun flush(context: Context) {
        val events = synchronized(lock) { load(context) }
        if (events.length() == 0) return
        val baseUrl = ApiClient.baseUrl(context) ?: run {
            Log.w(TAG, "Backend URL not set — ${events.length()} SMS event(s) wait")
            flushSoon(context, 10_000L)
            return
        }
        val done = HashSet<String>()
        var failed = false
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val code = try {
                val req = Request.Builder()
                    .url("$baseUrl/api/sms/event")
                    .post(e.toString().toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { it.code }
            } catch (ex: Exception) {
                Log.w(TAG, "POST sms ${e.optString("event")} failed: ${ex.message}")
                -1
            }
            Log.d(TAG, "POST sms ${e.optString("event")} ${e.optString("status")} → $code")
            val final = code in 200..299 || (code in 400..499 && code != 408 && code != 429)
            if (!final) { failed = true; break } // keep the order: received before its status
            done.add(keyOf(e))
        }
        synchronized(lock) {
            val current = load(context)
            val next = JSONArray()
            for (i in 0 until current.length()) {
                val e = current.optJSONObject(i) ?: continue
                if (keyOf(e) !in done) next.put(e)
            }
            save(context, next)
        }
        if (failed) {
            backoffMs = (backoffMs * 2).coerceIn(2_000L, 60_000L)
            flushSoon(context, backoffMs)
        } else backoffMs = 0L
    }

    private fun load(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return try { if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun save(context: Context, events: JSONArray) {
        // commit(): the event must be on disk before the receiver returns.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, events.toString()).commit()
    }
}
