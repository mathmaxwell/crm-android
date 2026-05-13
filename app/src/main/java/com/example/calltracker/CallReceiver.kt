package com.example.calltracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Receives PHONE_STATE broadcasts and emits 3 events per incoming call:
 *   1. RINGING  → "ringing"  (with caller number — resolved via CallLog/root if hidden)
 *   2. OFFHOOK  → "answered" + starts CallStreamService (live audio + WAV recording)
 *   3. IDLE     → stops the service (which itself posts "ended" with the WAV file)
 *
 * Outgoing calls (OFFHOOK without prior RINGING) are intentionally ignored.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"

        @Volatile private var lastState: String = TelephonyManager.EXTRA_STATE_IDLE
        @Volatile private var currentCallId: String? = null
        @Volatile private var incomingNumber: String? = null
        @Volatile private var ringStartMillis: Long = 0
        @Volatile private var answerMillis: Long = 0
        @Volatile private var streamStarted: Boolean = false

        private val resolverExecutor = Executors.newSingleThreadScheduledExecutor()
        private val main = Handler(Looper.getMainLooper())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed — receiver alive"); return
        }
        if (intent.action != "android.intent.action.PHONE_STATE") return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val app = context.applicationContext

        Log.d(TAG, "state=$state number=$number lastState=$lastState callId=$currentCallId")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (lastState != TelephonyManager.EXTRA_STATE_RINGING) {
                    val initial = number?.takeIf { it.isNotBlank() } ?: "unknown"
                    val callId = UUID.randomUUID().toString()
                    currentCallId = callId
                    incomingNumber = initial
                    ringStartMillis = System.currentTimeMillis()
                    answerMillis = 0
                    streamStarted = false
                    ApiClient.sendRinging(app, callId, initial, ringStartMillis)

                    if (initial == "unknown") scheduleNumberResolution(app, callId, ringStartMillis)
                } else if (!number.isNullOrBlank() && incomingNumber == "unknown") {
                    incomingNumber = number
                    currentCallId?.let { id -> ApiClient.patchCaller(app, id, number) }
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val callId = currentCallId
                if (callId != null
                    && lastState == TelephonyManager.EXTRA_STATE_RINGING
                    && answerMillis == 0L
                ) {
                    answerMillis = System.currentTimeMillis()
                    ApiClient.sendAnswered(app, callId, answerMillis)
                    startStreamService(app, callId)
                    streamStarted = true
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val callId = currentCallId
                if (callId != null && ringStartMillis > 0) {
                    val now = System.currentTimeMillis()
                    val durationSec = if (answerMillis > 0) (now - answerMillis) / 1000 else 0L
                    val status = when {
                        answerMillis > 0 -> "answered"
                        lastState == TelephonyManager.EXTRA_STATE_RINGING -> "missed"
                        else -> "rejected"
                    }
                    // Last-chance number resolution: now that the call is over the
                    // CallLog row is guaranteed to be present.
                    if (incomingNumber == "unknown") scheduleNumberResolution(app, callId, ringStartMillis)

                    if (streamStarted) {
                        stopStreamService(app, now, durationSec, status)
                    } else {
                        ApiClient.sendEnded(app, callId, now, durationSec, status, null)
                    }
                }
                currentCallId = null
                incomingNumber = null
                ringStartMillis = 0
                answerMillis = 0
                streamStarted = false
            }
        }
        lastState = state
    }

    private fun scheduleNumberResolution(context: Context, callId: String, sinceMs: Long) {
        // CallLog/dumpsys may need a moment to update.
        resolverExecutor.schedule({
            try {
                val resolved = CallNumberResolver.resolve(context, sinceMs)
                if (!resolved.isNullOrBlank() && resolved != "unknown") {
                    val current = incomingNumber
                    if (current == null || current == "unknown" || current != resolved) {
                        incomingNumber = resolved
                        main.post { ApiClient.patchCaller(context, callId, resolved) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Number resolution failed", e)
            }
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun startStreamService(context: Context, callId: String) {
        val intent = Intent(context, CallStreamService::class.java).apply {
            action = CallStreamService.ACTION_START
            putExtra(CallStreamService.EXTRA_CALL_ID, callId)
            putExtra(CallStreamService.EXTRA_BASE_URL, ApiClient.baseUrl(context))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopStreamService(context: Context, atMs: Long, durationSec: Long, status: String) {
        val intent = Intent(context, CallStreamService::class.java).apply {
            action = CallStreamService.ACTION_STOP
            putExtra(CallStreamService.EXTRA_AT_MILLIS, atMs)
            putExtra(CallStreamService.EXTRA_DURATION_SEC, durationSec)
            putExtra(CallStreamService.EXTRA_STATUS, status)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
