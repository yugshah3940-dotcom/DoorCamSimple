package com.example.doorcamsimple

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private val CAMERA_PERMISSION = Manifest.permission.CAMERA

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startService()
        else tvStatus.text = "Status: Camera permission required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { ensurePermissionAndStart() }
        btnStop.setOnClickListener { stopService() }

        tvStatus.text = "Status: Ready"
    }

    private fun ensurePermissionAndStart() {
        if (ActivityCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startService()
        } else {
            requestPermissionLauncher.launch(CAMERA_PERMISSION)
        }
    }

    private fun startService() {
        val svc = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        tvStatus.text = "Status: Running (service started)"
        // prompt user to disable battery optimizations to keep service alive
        try {
            val intent = Intent()
            intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            startActivity(intent)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun stopService() {
        val svc = Intent(this, CameraService::class.java)
        stopService(svc)
        tvStatus.text = "Status: Stopped"
    }
}
