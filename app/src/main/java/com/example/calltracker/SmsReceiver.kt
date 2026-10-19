package com.example.calltracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * Every SMS the phone receives goes to the CRM («Чаты → SMS»). A long SMS
 * arrives in one broadcast with all its parts; they are joined per sender.
 * The id is derived from sender, time and text, so the same SMS delivered
 * twice is stored once by the backend.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts: Array<SmsMessage?> = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        } catch (e: Exception) {
            Log.e(TAG, "unreadable SMS", e)
            return
        }
        val bySender = LinkedHashMap<String, MutableList<SmsMessage>>()
        for (p in parts) {
            if (p == null) continue
            val from = p.displayOriginatingAddress ?: p.originatingAddress ?: continue
            bySender.getOrPut(from) { mutableListOf() }.add(p)
        }
        for ((from, list) in bySender) {
            val text = list.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            val at = list.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
            val id = UUID.nameUUIDFromBytes("$from|$at|$text".toByteArray()).toString()
            SmsOutbox.add(
                context,
                JSONObject()
                    .put("event", "received")
                    .put("sms_id", id)
                    .put("phone", from)
                    .put("text", text)
                    .put("at", SmsOutbox.iso(at))
                    .put("parts", list.size),
            )
            Log.i(TAG, "SMS from $from (${list.size} part(s), id=${id.take(8)})")
        }
    }
}
