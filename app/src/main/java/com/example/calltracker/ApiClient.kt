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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun baseUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        return prefs.getString(MainActivity.KEY_URL, null)
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

    fun sendEnded(
        context: Context,
        callId: String,
        atMillis: Long,
        durationSec: Long,
        status: String,
        audioFile: File?,
    ) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("event", "ended")
            .addFormDataPart("call_id", callId)
            .addFormDataPart("at", isoFormatter.format(Date(atMillis)))
            .addFormDataPart("duration_sec", durationSec.toString())
            .addFormDataPart("status", status)

        if (audioFile != null && audioFile.exists() && audioFile.length() > 44) {
            val mime = if (audioFile.name.endsWith(".wav", true)) "audio/wav" else "audio/mpeg"
            builder.addFormDataPart(
                "audio", audioFile.name, audioFile.asRequestBody(mime.toMediaType())
            )
        }
        post(context, "/api/calls/event", builder.build(),
            "ended status=$status dur=${durationSec}s audio=${audioFile?.length() ?: 0}b")
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
            val request = Request.Builder()
                .url(url)
                .header(NGROK_HEADER, "true")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "POST $url [$label] → ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "POST $url [$label] failed", e)
            }
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
