package com.remotephone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.security.SecureRandom

class MainActivity : ComponentActivity() {

    private lateinit var serverInput: EditText
    private lateinit var codeText: TextView
    private lateinit var accessText: TextView
    private var code = ""

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenShareService::class.java).apply {
                    action = ScreenShareService.ACTION_START
                    putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_DATA, result.data)
                    putExtra(ScreenShareService.EXTRA_SERVER, serverInput.text.toString().trim())
                    putExtra(ScreenShareService.EXTRA_CODE, code)
                }
                startForegroundService(intent)
                Toast.makeText(this, "Sharing started. Enter the code on your computer.", Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("remotephone", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = pad / 2 }

        root.addView(TextView(this).apply { text = "RemotePhone"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "Control this phone from your own computer. Nothing is shared until you tap Start sharing."
        }, lp())

        serverInput = EditText(this).apply {
            hint = "Server URL, e.g. wss://your-server.example.com"
            setText(prefs.getString("server", ""))
            setSingleLine()
        }
        root.addView(serverInput, lp())

        accessText = TextView(this)
        root.addView(accessText, lp())
        root.addView(Button(this).apply {
            text = "1. Enable control (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, lp())

        codeText = TextView(this).apply { textSize = 34f; gravity = Gravity.CENTER; letterSpacing = 0.15f }
        root.addView(codeText, lp())

        root.addView(Button(this).apply {
            text = "2. Start sharing"
            setOnClickListener {
                val server = serverInput.text.toString().trim()
                if (!server.startsWith("ws://") && !server.startsWith("wss://")) {
                    Toast.makeText(this@MainActivity, "Server URL must start with wss:// (or ws://)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("server", server).apply()
                newCode()
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }, lp())

        root.addView(Button(this).apply {
            text = "Stop sharing"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
                codeText.text = ""
            }
        }, lp())

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = RemoteAccessibilityService.instance != null
        accessText.text = if (on) "Control: enabled" else "Control: not enabled yet (screen viewing works, taps do not)"
    }

    private fun newCode() {
        val r = SecureRandom()
        code = (0 until 8).joinToString("") { r.nextInt(10).toString() }
        codeText.text = code
    }
}
