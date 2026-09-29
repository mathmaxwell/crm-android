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
 * Tracks one call through PHONE_STATE broadcasts:
 *
 *   Incoming:  IDLE → RINGING → (OFFHOOK) → IDLE
 *     1. RINGING  → POST "ringing" (caller resolved via CallLog if hidden)
 *     2. OFFHOOK  → POST "answered"
 *     3. IDLE     → start [CallUploadService] to find/upload Mi Dialer's mp3
 *
 *   Outgoing:  IDLE → OFFHOOK → IDLE
 *     OFFHOOK fires straight from IDLE. We pair it with the most recent
 *     [CallReceiver.pendingOutgoing] record stashed by CommandService.dial(),
 *     which knows the dialed number. "ringing" + "answered" are posted up
 *     front so the frontend widget flips to the talking state and exposes the
 *     hang-up button.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        /** Time window in which an OFFHOOK following a dial command will be
         *  treated as belonging to that dial. Anything later is ignored. */
        private const val OUTGOING_MATCH_WINDOW_MS = 30_000L

        @Volatile private var lastState: String = TelephonyManager.EXTRA_STATE_IDLE
        @Volatile private var currentCallId: String? = null
        @Volatile private var incomingNumber: String? = null
        @Volatile private var ringStartMillis: Long = 0
        @Volatile private var answerMillis: Long = 0

        /** Set by [CommandService] right before it fires ACTION_CALL so this
         *  receiver can associate the resulting OFFHOOK with the dialed phone. */
        @Volatile var pendingOutgoing: PendingOutgoing? = null

        data class PendingOutgoing(
            val callId: String,
            val phone: String,
            val createdAt: Long,
        )

        /** Safety net for missed IDLE broadcasts: when [CommandService] polls
         *  [TelephonyManager.getCallState] and observes the phone is idle but
         *  we still have an active call_id, it calls this to flush "ended" and
         *  clean up our state — so the dashboard widget never gets stuck in
         *  the talking mode. Returns true if state was actually flushed. */
        fun forceEndIfIdle(context: Context, phoneStateIdle: Boolean): Boolean {
            if (!phoneStateIdle) return false
            val now = System.currentTimeMillis()
            val callId = currentCallId
            if (callId != null && ringStartMillis > 0) {
                val durationSec = if (answerMillis > 0) (now - answerMillis) / 1000 else 0L
                val status = if (answerMillis > 0) "answered" else "missed"
                Log.w(TAG, "forceEndIfIdle: flushing stale call $callId (broadcast missed?)")
                ApiClient.sendEnded(context.applicationContext, callId, now, durationSec, status)
                currentCallId = null
                incomingNumber = null
                ringStartMillis = 0
                answerMillis = 0
                pendingOutgoing = null
                return true
            }
            // OFFHOOK never fired after a dial command — the call placement
            // must have failed (user cancelled the dialer, no signal, etc).
            // Flush the orphaned pendingOutgoing so the dashboard doesn't show
            // a permanent "talking" state for a call that never happened.
            val pending = pendingOutgoing
            if (pending != null && now - pending.createdAt > 5_000L) {
                Log.w(TAG, "forceEndIfIdle: flushing orphaned pendingOutgoing ${pending.callId}")
                ApiClient.sendEnded(context.applicationContext, pending.callId, now, 0, "rejected")
                pendingOutgoing = null
                return true
            }
            return false
        }

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
                    // Incoming call answered.
                    answerMillis = System.currentTimeMillis()
                    ApiClient.sendAnswered(app, callId, answerMillis)
                } else if (callId == null && lastState == TelephonyManager.EXTRA_STATE_IDLE) {
                    // Outgoing call (no RINGING preceded). Pair with the most
                    // recent dial command if we got one in time.
                    val now = System.currentTimeMillis()
                    val pending = pendingOutgoing
                    if (pending != null && now - pending.createdAt < OUTGOING_MATCH_WINDOW_MS) {
                        currentCallId = pending.callId
                        incomingNumber = pending.phone
                        ringStartMillis = pending.createdAt
                        answerMillis = now
                        pendingOutgoing = null
                        // CommandService already POSTed "ringing"+"answered"; nothing more to send here.
                        Log.i(TAG, "Outgoing OFFHOOK paired with dial → ${pending.phone}")
                    } else {
                        Log.d(TAG, "OFFHOOK without RINGING and no pending dial — ignored")
                    }
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
                    if (incomingNumber == "unknown") scheduleNumberResolution(app, callId, ringStartMillis)

                    // Flip status now so the dashboard widget exits the
                    // talking state immediately. Audio (if any) is attached
                    // later by CallUploadService via the dedicated endpoint.
                    ApiClient.sendEnded(app, callId, now, durationSec, status)

                    if (answerMillis > 0) {
                        startUploadService(
                            app, callId, incomingNumber ?: "unknown", answerMillis, now,
                        )
                    }
                }
                currentCallId = null
                incomingNumber = null
                ringStartMillis = 0
                answerMillis = 0
            }
        }
        lastState = state
    }

    private fun scheduleNumberResolution(context: Context, callId: String, sinceMs: Long) {
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

    private fun startUploadService(
        context: Context,
        callId: String,
        caller: String,
        callStartMs: Long,
        callEndMs: Long,
    ) {
        val intent = Intent(context, CallUploadService::class.java).apply {
            action = CallUploadService.ACTION_UPLOAD
            putExtra(CallUploadService.EXTRA_CALL_ID, callId)
            putExtra(CallUploadService.EXTRA_CALLER, caller)
            putExtra(CallUploadService.EXTRA_CALL_START_MS, callStartMs)
            putExtra(CallUploadService.EXTRA_CALL_END_MS, callEndMs)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
