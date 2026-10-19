package com.example.calltracker

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Sends the SMS the CRM hands over the command socket
 * (`{ type: "command", action: "sms", sms_id, phone, text }`) from the
 * phone's SIM and reports sending → sent → delivered, or failed, through
 * [SmsOutbox].
 *
 * An id is sent once: the backend repeats a command whose confirmation it
 * did not get, and then only the status it reached goes back.
 */
object SmsSender {
    private const val TAG = "SmsSender"
    const val ACTION_SENT = "com.example.calltracker.action.SMS_SENT"
    const val ACTION_DELIVERED = "com.example.calltracker.action.SMS_DELIVERED"
    const val EXTRA_ID = "sms_id"
    private const val PREFS = "sms_sent" // sms_id -> {"status","parts","sent","delivered","error","t"}
    private const val MAX_KEPT = 300

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun send(context: Context, smsId: String, phone: String, text: String) {
        val app = context.applicationContext
        if (smsId.isBlank() || phone.isBlank()) { Log.w(TAG, "sms: empty id or phone"); return }
        val known = state(app, smsId)
        if (known != null) {
            Log.i(TAG, "sms ${smsId.take(8)} already handled (${known.optString("status")}) — status only")
            report(app, smsId, known.optString("status", "sending"), known.optString("error").ifBlank { null })
            return
        }
        if (!hasPermission(app)) {
            save(app, smsId, JSONObject().put("status", "failed").put("error", "no_permission"))
            report(app, smsId, "failed", "no_permission", "SEND_SMS not granted")
            return
        }
        val sm = smsManager(app)
        val parts = sm.divideMessage(text)
        save(app, smsId, JSONObject().put("status", "sending").put("parts", parts.size).put("sent", 0).put("delivered", 0))
        report(app, smsId, "sending", parts = parts.size)
        val sent = ArrayList<PendingIntent>()
        val delivered = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            sent.add(pending(app, ACTION_SENT, smsId, i))
            delivered.add(pending(app, ACTION_DELIVERED, smsId, i))
        }
        try {
            if (parts.size == 1) sm.sendTextMessage(phone, null, parts[0], sent[0], delivered[0])
            else sm.sendMultipartTextMessage(phone, null, parts, sent, delivered)
            Log.i(TAG, "sms ${smsId.take(8)} → $phone (${parts.size} part(s))")
        } catch (e: Exception) {
            Log.e(TAG, "sms send failed", e)
            save(app, smsId, JSONObject().put("status", "failed").put("error", "send_failed"))
            report(app, smsId, "failed", "send_failed", e.message)
        }
    }

    /** A part left the phone (or did not). */
    @Synchronized
    fun onSent(context: Context, smsId: String, resultCode: Int) {
        val st = state(context, smsId) ?: return
        if (st.optString("status") == "failed") return
        if (resultCode == Activity.RESULT_OK) {
            val n = st.optInt("sent") + 1
            st.put("sent", n)
            if (n >= st.optInt("parts", 1) && st.optString("status") == "sending") {
                st.put("status", "sent")
                save(context, smsId, st)
                report(context, smsId, "sent", parts = st.optInt("parts", 1))
            } else save(context, smsId, st)
            return
        }
        val error = when (resultCode) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit"
            else -> "generic"
        }
        st.put("status", "failed").put("error", error)
        save(context, smsId, st)
        report(context, smsId, "failed", error, "resultCode=$resultCode")
    }

    /** The operator's delivery report for a part (not every operator sends one). */
    @Synchronized
    fun onDelivered(context: Context, smsId: String, intent: Intent) {
        val st = state(context, smsId) ?: return
        if (st.optString("status") == "failed" || st.optString("status") == "delivered") return
        val status = try {
            val pdu = intent.getByteArrayExtra("pdu")
            if (pdu == null) 0
            else {
                val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"))
                else @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu)
                msg?.status ?: 0
            }
        } catch (_: Exception) { 0 }
        // GSM TP-Status: 0x00–0x1F delivered, 0x20–0x3F still trying, 0x40+ failed.
        if (status >= 0x40) {
            st.put("status", "failed").put("error", "not_delivered")
            save(context, smsId, st)
            report(context, smsId, "failed", "not_delivered", "TP-Status=$status")
            return
        }
        if (status >= 0x20) return
        val n = st.optInt("delivered") + 1
        st.put("delivered", n)
        if (n >= st.optInt("parts", 1)) {
            st.put("status", "delivered")
            report(context, smsId, "delivered")
        }
        save(context, smsId, st)
    }

    private fun report(
        context: Context,
        smsId: String,
        status: String,
        error: String? = null,
        detail: String? = null,
        parts: Int? = null,
    ) {
        val e = JSONObject()
            .put("event", "status")
            .put("sms_id", smsId)
            .put("status", status)
            .put("at", SmsOutbox.iso(System.currentTimeMillis()))
        if (error != null) e.put("error", error)
        if (!detail.isNullOrBlank()) e.put("detail", detail.take(200))
        if (parts != null) e.put("parts", parts)
        SmsOutbox.add(context, e)
    }

    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    // The data URI makes each part's PendingIntent distinct; mutable so the
    // system can attach the delivery report's pdu.
    private fun pending(context: Context, action: String, smsId: String, part: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("sms-status://$smsId/$part"))
            .putExtra(EXTRA_ID, smsId)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun state(context: Context, smsId: String): JSONObject? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(smsId, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun save(context: Context, smsId: String, st: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!st.has("t")) st.put("t", System.currentTimeMillis())
        val edit = prefs.edit().putString(smsId, st.toString())
        // Keep the newest MAX_KEPT ids: enough to recognise any repeat.
        val all = prefs.all
        if (all.size > MAX_KEPT) {
            all.entries
                .filter { it.key != smsId }
                .map { it.key to (try { JSONObject(it.value as String).optLong("t") } catch (_: Exception) { 0L }) }
                .sortedBy { it.second }
                .take(all.size - MAX_KEPT + 1)
                .forEach { edit.remove(it.first) }
        }
        edit.commit()
    }
}

/** Results of [SmsSender]'s sent and delivered PendingIntents. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val smsId = intent.getStringExtra(SmsSender.EXTRA_ID) ?: return
        when (intent.action) {
            SmsSender.ACTION_SENT -> SmsSender.onSent(context, smsId, resultCode)
            SmsSender.ACTION_DELIVERED -> SmsSender.onDelivered(context, smsId, intent)
        }
    }
}
