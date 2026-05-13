package com.example.calltracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "settings"
        const val KEY_URL = "backend_url"
        const val DEFAULT_URL = "https://cloddily-equilibristic-renna.ngrok-free.dev"
        private const val PERMISSION_REQUEST = 1001
    }

    private lateinit var urlEdit: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlEdit = findViewById(R.id.urlEdit)
        statusText = findViewById(R.id.statusText)
        val saveBtn = findViewById<Button>(R.id.saveBtn)
        val testBtn = findViewById<Button>(R.id.testBtn)
        val batteryBtn = findViewById<Button>(R.id.batteryBtn)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        urlEdit.setText(prefs.getString(KEY_URL, DEFAULT_URL))

        saveBtn.setOnClickListener {
            val url = urlEdit.text.toString().trim().trimEnd('/')
            if (url.isEmpty()) {
                Toast.makeText(this, "URL is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_URL, url).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        testBtn.setOnClickListener { sendTestSequence() }

        batteryBtn.setOnClickListener { requestIgnoreBattery() }

        requestPermissions()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    /** Simulates a full incoming call: ringing → answered (after 2s) → ended (after 6s). */
    private fun sendTestSequence() {
        val callId = UUID.randomUUID().toString()
        val caller = "+998901234567"
        val ringingAt = System.currentTimeMillis()

        ApiClient.sendRinging(this, callId, caller, ringingAt)
        Toast.makeText(this, "Test: ringing…", Toast.LENGTH_SHORT).show()

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            val answeredAt = System.currentTimeMillis()
            ApiClient.sendAnswered(this, callId, answeredAt)
            Toast.makeText(this, "Test: answered", Toast.LENGTH_SHORT).show()

            handler.postDelayed({
                val endedAt = System.currentTimeMillis()
                ApiClient.sendEnded(
                    this, callId, endedAt,
                    durationSec = 4, status = "answered", audioFile = null
                )
                Toast.makeText(this, "Test: ended", Toast.LENGTH_SHORT).show()
            }, 4_000)
        }, 2_000)
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, DEFAULT_URL)
        val perms = requiredPermissions()
        val granted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        statusText.text = buildString {
            append("Backend URL:\n$url\n\n")
            append("Permissions: ${if (granted) "✅ granted" else "❌ missing"}\n")
            append("Battery optimization: ${if (batteryOk) "✅ ignored" else "⚠️ active (may kill receiver)"}\n")
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun requestPermissions() {
        val perms = requiredPermissions()
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Already ignored", Toast.LENGTH_SHORT).show()
        }
    }
}
