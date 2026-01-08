package com.margelo.nitro.audiorecorderplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Timer
import java.util.TimerTask
import kotlin.math.log10

/**
 * Foreground Service that owns and manages MediaRecorder
 * Required for Android 9+ to record audio in background
 * MediaRecorder runs INSIDE this service to ensure it works when screen is off
 */
class RecordingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordTimer: Timer? = null
    
    // Recording state
    private var currentRecordingPath: String? = null
    private var recordStartTime: Long = 0L
    private var pausedRecordTime: Long = 0L
    private var isRecording: Boolean = false
    private var isPaused: Boolean = false
    private var meteringEnabled: Boolean = false
    
    // Metering
    private var lastMeteringUpdateTime = 0L
    private var lastMeteringValue = SILENCE_THRESHOLD_DB
    
    // Callback for recording updates
    var onRecordingUpdate: ((isRecording: Boolean, currentPosition: Double, metering: Double?) -> Unit)? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val binder = RecordingBinder()
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"
        private const val CHANNEL_NAME = "Audio Recording"
        private const val WAKE_LOCK_TAG = "RecordingForegroundService::WakeLock"
        
        // Audio constants
        private const val MIN_AMPLITUDE_EPSILON = 1e-10
        private const val SILENCE_THRESHOLD_DB = -160.0
        private const val MAX_AMPLITUDE_VALUE = 32767.0
        private const val METERING_UPDATE_INTERVAL_MS = 100L
        private const val MAX_DECIBEL_LEVEL = 0.0
        private const val METERING_DISABLED_VALUE = 0.0
        
        private var instance: RecordingForegroundService? = null
        
        fun getInstance(): RecordingForegroundService? = instance
        
        fun start(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            instance?.stopRecordingInternal()
            val intent = Intent(context, RecordingForegroundService::class.java)
            context.stopService(intent)
        }
    }
    
    inner class RecordingBinder : Binder() {
        fun getService(): RecordingForegroundService = this@RecordingForegroundService
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        stopRecordingInternal()
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }
    
    /**
     * Called when user swipes away the app from recents.
     * This ensures the audio file is properly finalized so it can be opened later.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Finalize the audio file before the app is killed
        finalizeRecordingOnKill()
        super.onTaskRemoved(rootIntent)
        // Stop the service after finalizing
        stopSelf()
    }
    
    /**
     * Safely finalize the recording when app is being killed.
     * This ensures the audio file header is written correctly and the file can be played back.
     */
    private fun finalizeRecordingOnKill() {
        try {
            if (isRecording || isPaused) {
                Logger.d("[ForegroundService] Finalizing recording on app kill...")
                
                stopRecordTimer()
                
                mediaRecorder?.apply {
                    try {
                        // Stop the recorder to write file headers
                        stop()
                        Logger.d("[ForegroundService] MediaRecorder stopped successfully")
                    } catch (e: Exception) {
                        Logger.e("[ForegroundService] Error stopping MediaRecorder: ${e.message}", e)
                    }
                    try {
                        release()
                        Logger.d("[ForegroundService] MediaRecorder released successfully")
                    } catch (e: Exception) {
                        Logger.e("[ForegroundService] Error releasing MediaRecorder: ${e.message}", e)
                    }
                }
                mediaRecorder = null
                
                // Log the saved file path for debugging
                currentRecordingPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        Logger.d("[ForegroundService] Audio file saved: $path (${file.length()} bytes)")
                    } else {
                        Logger.e("[ForegroundService] Audio file not found after finalization: $path")
                    }
                }
                
                isRecording = false
                isPaused = false
            }
        } catch (e: Exception) {
            Logger.e("[ForegroundService] Error finalizing recording on kill: ${e.message}", e)
        }
    }
    
    // ==================== Recording Methods ====================
    
    fun startRecording(
        filePath: String,
        audioSource: Int,
        outputFormat: Int,
        audioEncoder: Int,
        samplingRate: Int?,
        channels: Int?,
        bitrate: Int?,
        enableMetering: Boolean,
        subscriptionDuration: Long
    ): Boolean {
        try {
            // Stop any existing recording
            stopRecordingInternal()
            
            currentRecordingPath = filePath
            meteringEnabled = enableMetering
            
            // Initialize MediaRecorder INSIDE the service
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(audioSource)
                setOutputFormat(outputFormat)
                setAudioEncoder(audioEncoder)
                
                samplingRate?.let { setAudioSamplingRate(it) }
                channels?.let { setAudioChannels(it) }
                bitrate?.let { setAudioEncodingBitRate(it) }
                
                setOutputFile(filePath)
                
                prepare()
                start()
            }
            
            recordStartTime = System.currentTimeMillis()
            pausedRecordTime = 0L
            isRecording = true
            isPaused = false
            
            // Start timer for recording updates
            startRecordTimer(subscriptionDuration)
            
            // Update notification
            updateNotification("Recording in progress...")
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupRecorder()
            return false
        }
    }
    
    fun pauseRecording(): Boolean {
        if (!isRecording || isPaused) return false
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                pausedRecordTime = System.currentTimeMillis() - recordStartTime
                isPaused = true
                isRecording = false
                stopRecordTimer()
                updateNotification("Recording paused")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun resumeRecording(): Boolean {
        if (!isPaused) return false
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                recordStartTime = System.currentTimeMillis() - pausedRecordTime
                isPaused = false
                isRecording = true
                startRecordTimer(60L) // Default, will be updated
                updateNotification("Recording in progress...")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun stopRecording(): String? {
        val path = currentRecordingPath
        stopRecordingInternal()
        return path
    }
    
    private fun stopRecordingInternal() {
        try {
            stopRecordTimer()
            
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    // Ignore stop errors
                }
                try {
                    release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            }
            mediaRecorder = null
            
            isRecording = false
            isPaused = false
            
            // Reset metering
            meteringEnabled = false
            lastMeteringUpdateTime = 0L
            lastMeteringValue = SILENCE_THRESHOLD_DB
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaRecorder = null
        isRecording = false
        isPaused = false
        currentRecordingPath = null
    }
    
    fun getRecordingPath(): String? = currentRecordingPath
    
    fun isCurrentlyRecording(): Boolean = isRecording
    
    fun isCurrentlyPaused(): Boolean = isPaused
    
    // ==================== Timer ====================
    
    private var subscriptionDurationMs: Long = 60L
    
    private fun startRecordTimer(durationMs: Long) {
        subscriptionDurationMs = durationMs
        recordTimer?.cancel()
        recordTimer = Timer()
        recordTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!isRecording || mediaRecorder == null) {
                    return
                }
                
                val currentTime = System.currentTimeMillis() - recordStartTime
                val meteringValue = if (meteringEnabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastMeteringUpdateTime >= METERING_UPDATE_INTERVAL_MS) {
                        lastMeteringValue = getSimpleMetering()
                        lastMeteringUpdateTime = now
                    }
                    lastMeteringValue
                } else {
                    METERING_DISABLED_VALUE
                }
                
                handler.post {
                    onRecordingUpdate?.invoke(isRecording, currentTime.toDouble(), meteringValue)
                }
            }
        }, 0, durationMs)
    }
    
    private fun stopRecordTimer() {
        recordTimer?.cancel()
        recordTimer = null
    }
    
    private fun getSimpleMetering(): Double {
        return try {
            val maxAmplitude = mediaRecorder?.maxAmplitude ?: 0
            if (maxAmplitude > 0) {
                val normalizedAmplitude = maxAmplitude.toDouble() / MAX_AMPLITUDE_VALUE
                val safeAmplitude = maxOf(normalizedAmplitude, MIN_AMPLITUDE_EPSILON)
                val decibels = 20 * log10(safeAmplitude)
                maxOf(SILENCE_THRESHOLD_DB, minOf(MAX_DECIBEL_LEVEL, decibels))
            } else {
                SILENCE_THRESHOLD_DB
            }
        } catch (e: Exception) {
            SILENCE_THRESHOLD_DB
        }
    }
    
    // ==================== Notification ====================
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio recording in progress"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String = "Tap to return to app"): Notification {
        val packageName = packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            pendingIntentFlags
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording audio")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    // ==================== WakeLock ====================
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            // Ignore
        }
    }
}
