package com.example.calltracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-running foreground service that holds an open WebSocket to the backend
 * and listens for `{ type: "command", call_id, action }` messages. On
 * `action=answer` it calls `TelecomManager.acceptRingingCall()`; on
 * `action=reject` it calls `TelecomManager.endCall()`.
 *
 * Both APIs require the runtime `ANSWER_PHONE_CALLS` permission (granted from
 * MainActivity).
 */
class CommandService : Service() {

    companion object {
        private const val TAG = "CommandService"
        private const val NOTIF_ID = 4244
        private const val CHANNEL_ID = "command_listener"
        /** How long the polled state must stay IDLE before the reconciler
         *  ends a call the receiver still thinks is live. */
        private const val RECONCILE_IDLE_MS = 10_000L

        const val ACTION_START = "com.example.calltracker.action.START_COMMAND"

        /** Convenience helper to start the service. */
        fun start(ctx: Context) {
            val intent = Intent(ctx, CommandService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    private val stopping = AtomicBoolean(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var pollStarted = false
    private var idleSinceElapsed = 0L
    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            try { pollPhoneState() } catch (_: Exception) {}
            if (!stopping.get()) mainHandler.postDelayed(this, 2_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        connect()
        if (!pollStarted) {
            pollStarted = true
            mainHandler.postDelayed(pollRunnable, 2_000L)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        mainHandler.removeCallbacks(pollRunnable)
        try { webSocket?.close(1000, "service stopped") } catch (_: Exception) {}
        webSocket = null
        super.onDestroy()
    }

    /** Safety net: PHONE_STATE broadcasts can be dropped (battery savers,
     *  background restrictions). Poll TelephonyManager every 2 s and force the
     *  receiver to flush its state if it still thinks there's an active call
     *  but the phone has been idle for a while.
     *
     *  A single IDLE reading is not trusted: while MIUI screens an incoming
     *  call (spam check) the polled state reads IDLE even though RINGING was
     *  already broadcast, which used to end live calls ~2 s after they
     *  started. Flush only after IDLE held for [RECONCILE_IDLE_MS] with no
     *  PHONE_STATE broadcast in between. */
    @SuppressLint("MissingPermission")
    private fun pollPhoneState() {
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val state = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.callStateForSubscription
            else @Suppress("DEPRECATION") tm.callState
        } catch (_: SecurityException) {
            @Suppress("DEPRECATION") tm.callState
        }
        val now = SystemClock.elapsedRealtime()
        if (state != TelephonyManager.CALL_STATE_IDLE) {
            idleSinceElapsed = 0L
            return
        }
        // The streak must start after the latest broadcast, so an old idle
        // period never counts toward a call that just started ringing.
        if (idleSinceElapsed == 0L || idleSinceElapsed < CallReceiver.lastBroadcastElapsed) {
            idleSinceElapsed = now
        }
        if (now - idleSinceElapsed < RECONCILE_IDLE_MS) return
        if (CallReceiver.forceEndIfIdle(applicationContext, true)) {
            Log.i(TAG, "Reconciler flushed a stale call (phone IDLE for ${now - idleSinceElapsed} ms)")
        }
    }

    private fun connect() {
        if (stopping.get()) return
        // Idempotent: if a socket already exists, do nothing. Without this
        // guard onStartCommand getting called twice (onCreate + onResume, or
        // a sticky restart) opens parallel WS, each delivering every command
        // — which caused duplicate "dial" -> two stuck calls on the server.
        if (webSocket != null) {
            Log.d(TAG, "WS already alive — skipping connect()")
            return
        }
        val baseUrl = ApiClient.baseUrl(applicationContext)
        if (baseUrl.isNullOrBlank()) {
            Log.w(TAG, "Backend URL not set — cannot connect WS")
            scheduleReconnect()
            return
        }
        val wsUrl = baseUrl
            .replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
            .trimEnd('/') + "/ws?role=apk"

        Log.i(TAG, "Connecting WS → $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        webSocket = httpClient.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (stopping.get()) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        val delayMs = (1000L * (1 shl reconnectAttempt)).coerceAtMost(30_000L)
        Log.i(TAG, "Reconnect in ${delayMs}ms (attempt $reconnectAttempt)")
        mainHandler.postDelayed({ connect() }, delayMs)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS opened (${response.code})")
            reconnectAttempt = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "WS ← $text")
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // ignore binary
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: $code $reason")
            this@CommandService.webSocket = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message} (response=${response?.code})")
            this@CommandService.webSocket = null
            scheduleReconnect()
        }
    }

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        if (json.optString("type") != "command") return
        val action = json.optString("action")
        Log.i(TAG, "COMMAND $action")

        when (action) {
            "answer" -> acceptIncoming()
            "reject" -> rejectIncoming()
            "dial"   -> dial(json.optString("phone"))
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    @SuppressLint("MissingPermission")
    private fun dial(phone: String) {
        if (phone.isBlank()) { Log.w(TAG, "dial: empty phone"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "dial: CALL_PHONE not granted"); return
        }

        // Register the outgoing call up front so the frontend widget flips to
        // "talking" mode immediately and exposes the hang-up button. The actual
        // OFFHOOK broadcast (a moment later) gets paired to this call_id by
        // CallReceiver via pendingOutgoing.
        val callId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        CallReceiver.pendingOutgoing = CallReceiver.Companion.PendingOutgoing(callId, phone, now)
        ApiClient.sendRinging(applicationContext, callId, phone, now)
        ApiClient.sendAnswered(applicationContext, callId, now, caller = phone)

        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phone, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.i(TAG, "dial → $phone (callId=${callId.take(8)})")
        } catch (e: Exception) {
            Log.e(TAG, "dial failed", e)
            // Clean up so a future broken dial doesn't leave a stale pending.
            CallReceiver.pendingOutgoing = null
        }
    }

    private fun hasAnswerPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun acceptIncoming() {
        if (!hasAnswerPermission()) {
            Log.w(TAG, "Cannot accept — ANSWER_PHONE_CALLS not granted")
            return
        }
        val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: run {
            Log.w(TAG, "TelecomManager unavailable"); return
        }
        try {
            tm.acceptRingingCall()
            Log.i(TAG, "acceptRingingCall() invoked")
        } catch (e: Exception) {
            Log.e(TAG, "acceptRingingCall failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun rejectIncoming() {
        // TelecomManager.endCall() only accepts ANSWER_PHONE_CALLS from API 28;
        // on 8.x it demands the system-only MODIFY_PHONE_STATE. There the
        // hidden ITelephony.endCall() works with CALL_PHONE instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val getITelephony = TelephonyManager::class.java
                    .getDeclaredMethod("getITelephony").apply { isAccessible = true }
                val telephony = getITelephony.invoke(tm)
                val ended = telephony.javaClass.getMethod("endCall").invoke(telephony)
                Log.i(TAG, "ITelephony.endCall() invoked → $ended")
            } catch (e: Exception) {
                Log.e(TAG, "ITelephony.endCall failed", e)
            }
            return
        }

        if (!hasAnswerPermission()) {
            Log.w(TAG, "Cannot reject — ANSWER_PHONE_CALLS not granted")
            return
        }
        val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: run {
            Log.w(TAG, "TelecomManager unavailable"); return
        }
        try {
            @Suppress("DEPRECATION")
            tm.endCall()
            Log.i(TAG, "endCall() invoked")
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed", e)
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Call commands", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Call Tracker")
            .setContentText("Listening for answer/reject commands")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
