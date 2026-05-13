package com.example.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Resolves the real caller phone number when Android does not deliver it via
 * EXTRA_INCOMING_NUMBER (the default on Android 10+ for privacy).
 *
 * Resolution order:
 *   1. CallLog query for the most recent incoming row after [sinceMillis].
 *   2. Root shell:  `dumpsys telephony.registry` parses the active call number.
 */
object CallNumberResolver {
    private const val TAG = "NumberResolver"

    fun resolve(context: Context, sinceMillis: Long): String? {
        readFromCallLog(context, sinceMillis)?.let { return it }
        readFromTelephonyDumpsys()?.let { return it }
        return null
    }

    private fun readFromCallLog(context: Context, sinceMillis: Long): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
        val cutoff = (sinceMillis - 5_000L).coerceAtLeast(0L)
        val selection = "${CallLog.Calls.DATE} >= ?"
        val args = arrayOf(cutoff.toString())
        val sort = "${CallLog.Calls.DATE} DESC LIMIT 1"

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args, sort
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0)
                    number?.takeIf { it.isNotBlank() && it != "-1" && it != "-2" }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "CallLog query failed", e)
            null
        }
    }

    private fun readFromTelephonyDumpsys(): String? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys telephony.registry"))
        val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        proc.waitFor()
        // Lines look like:  mCallIncomingNumber=+998901234567   or   mIncomingNumber=...
        val regex = Regex("""(?:mCallIncomingNumber|mIncomingNumber)=([+\d][\d ()-]*)""")
        regex.find(output)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.d(TAG, "dumpsys telephony failed (no root?): ${e.message}")
        null
    }
}
