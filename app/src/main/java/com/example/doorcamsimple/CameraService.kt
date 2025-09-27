package com.example.doorcamsimple

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

class CameraService : LifecycleService() {

    private val TAG = "CameraService"
    private val CHANNEL_ID = "doorcam_channel_01"
    private val NOTIF_ID = 101
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    private var lastFrameAvg: Double? = null
    private val MOTION_THRESHOLD = 12.0
    private val COOLDOWN_MS = 5000L
    private var lastTriggerTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("DoorCam running"))
        startCamera()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val notifBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DoorCam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
        return notifBuilder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "DoorCam Service", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, { imageProxy ->
                            analyzeFrame(imageProxy)
                        })
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, analysisUseCase, imageCapture)
                Log.i(TAG, "Camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val yBuffer = imageProxy.planes[0].buffer
        val size = yBuffer.remaining()
        val sum = run {
            var s = 0L
            val step = 10
            var i = 0
            while (i < size) {
                s += (yBuffer.get(i).toInt() and 0xFF)
                i += step
            }
            s
        }
        val samples = size / 10.0
        val avg = sum / samples
        val last = lastFrameAvg
        if (last != null) {
            val diff = abs(avg - last)
            if (diff > MOTION_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    lastTriggerTime = now
                    Log.i(TAG, "Motion detected diff=$diff -> capture")
                    triggerCapture()
                }
            }
        }
        lastFrameAvg = avg
        imageProxy.close()
    }

    private fun triggerCapture() {
        val outDir = File(getExternalFilesDir(null), "captures")
        if (!outDir.exists()) outDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(outDir, "door_$ts.jpg")
        val outOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture?.takePicture(outOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                Log.i(TAG, "Saved: ${file.absolutePath}")
                playAlarm()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification("Captured: ${file.name}"))
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exception.message}")
            }
        })
    }

    private fun playAlarm() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(600)
            }
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {
            Log.e(TAG, "Alarm error: ${e.message}")
        }
    }
}
