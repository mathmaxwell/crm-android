package com.example.calltracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "settings"
        const val KEY_URL = "backend_url"
        const val DEFAULT_URL = "https://cloddily-equilibristic-renna.ngrok-free.dev"
        private const val PERMISSION_REQUEST = 1001

        private val MIUI_REC_DIR = File("/sdcard/MIUI/sound_recorder/call_rec")
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
        val filesBtn = findViewById<Button>(R.id.filesBtn)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_URL)) {
            prefs.edit().putString(KEY_URL, DEFAULT_URL).apply()
        }
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
        filesBtn.setOnClickListener { requestAllFilesAccess() }

        requestPermissions()
        CommandService.start(applicationContext)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        CommandService.start(applicationContext)
        updateStatus()
    }

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
                    this, callId, endedAt, durationSec = 4, status = "answered",
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
        val allFiles = hasAllFilesAccess()
        val recCount = countMiuiRecordings()

        statusText.text = buildString {
            append("Backend URL:\n$url\n\n")
            append("Permissions: ${if (granted) "✅ granted" else "❌ missing"}\n")
            append("Battery optimization: ${if (batteryOk) "✅ ignored" else "⚠️ active (may kill receiver)"}\n")
            append("All files access: ${if (allFiles) "✅ granted" else "❌ needed to read MIUI recordings"}\n")
            append(
                "MIUI recorder dir: " + when {
                    !allFiles -> "❓ unknown (no access)"
                    recCount < 0 -> "❌ not found — enable auto-record in Mi Dialer"
                    else -> "✅ $recCount file(s) present"
                } + "\n"
            )
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
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

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns -1 if the dir doesn't exist (auto-record never produced anything),
     *  otherwise the number of mp3 files currently in it. */
    private fun countMiuiRecordings(): Int {
        if (!hasAllFilesAccess()) return -1
        if (!MIUI_REC_DIR.exists()) return -1
        return MIUI_REC_DIR.listFiles { f ->
            f.isFile && f.name.endsWith(".mp3", ignoreCase = true)
        }?.size ?: -1
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Not required on this Android version", Toast.LENGTH_SHORT).show()
            return
        }
        if (Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
