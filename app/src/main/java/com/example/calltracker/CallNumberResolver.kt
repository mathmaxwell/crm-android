package com.example.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Resolves the real caller phone number when Android does not deliver it via
 * EXTRA_INCOMING_NUMBER (the default on Android 10+ for privacy).
 *
 * Queries CallLog for the most recent incoming row after [sinceMillis].
 */
object CallNumberResolver {
    private const val TAG = "NumberResolver"

    fun resolve(context: Context, sinceMillis: Long): String? =
        readFromCallLog(context, sinceMillis)

    private fun readFromCallLog(context: Context, sinceMillis: Long): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
        val cutoff = (sinceMillis - 5_000L).coerceAtLeast(0L)
        val selection = "${CallLog.Calls.DATE} >= ?"
        val args = arrayOf(cutoff.toString())
        // LIMIT in the sortOrder string was banned by the CallLog provider in
        // Android Q+ ("Invalid token LIMIT"). moveToFirst() already reads only
        // the first row, so we just drop the LIMIT clause.
        val sort = "${CallLog.Calls.DATE} DESC"

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
}
