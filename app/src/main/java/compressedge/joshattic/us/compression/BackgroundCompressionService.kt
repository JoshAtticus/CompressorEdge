package compressedge.joshattic.us.compression

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import compressedge.joshattic.us.MainActivity
import compressedge.joshattic.us.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a compression running while the app is in the
 * background. Experimental: results may vary across devices/OEMs.
 */
@OptIn(UnstableApi::class)
class BackgroundCompressionService : Service() {

    companion object {
        const val ACTION_START = "compressedge.joshattic.us.action.START_BACKGROUND_COMPRESSION"
        const val ACTION_START_BATCH = "compressedge.joshattic.us.action.START_BACKGROUND_COMPRESSION_BATCH"

        const val EXTRA_INPUT_URI = "extra_input_uri"
        const val EXTRA_OUTPUT_PATH = "extra_output_path"
        const val EXTRA_VIDEO_MIME = "extra_video_mime"
        const val EXTRA_OUTPUT_HEIGHT = "extra_output_height"
        const val EXTRA_OUTPUT_FPS = "extra_output_fps"
        const val EXTRA_ORIGINAL_WIDTH = "extra_original_width"
        const val EXTRA_ORIGINAL_HEIGHT = "extra_original_height"
        const val EXTRA_ORIGINAL_FPS = "extra_original_fps"
        const val EXTRA_ORIGINAL_SIZE = "extra_original_size"
        const val EXTRA_TARGET_BITRATE = "extra_target_bitrate"
        const val EXTRA_AUDIO_BITRATE = "extra_audio_bitrate"
        const val EXTRA_AUDIO_CODEC = "extra_audio_codec"
        const val EXTRA_REMOVE_AUDIO = "extra_remove_audio"
        const val EXTRA_AUDIO_VOLUME = "extra_audio_volume"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "background_compression"

        /** Stops an in-flight background compression. */
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, BackgroundCompressionService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transformer: Transformer? = null
    private var progressJob: Job? = null
    private var lastProgressPercent = -1
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startCompression(intent)
        } else if (intent?.action == ACTION_START_BATCH) {
            startBatchCompression(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        progressJob?.cancel()
        progressJob = null
        transformer?.cancel()
        transformer = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startCompression(intent: Intent) {
        if (transformer != null) return

        val inputUriString = intent.getStringExtra(EXTRA_INPUT_URI)
        val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        if (inputUriString.isNullOrBlank() || path.isNullOrBlank()) {
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_bg_starting), null),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_bg_starting), null)
            )
        }
        val originalSize = intent.getLongExtra(EXTRA_ORIGINAL_SIZE, 0L)
        BackgroundCompressionManager.setRunning(originalSize)

        val params = CompressionExecutor.Params(
            inputUri = Uri.parse(inputUriString),
            outputPath = path,
            videoMimeType = intent.getStringExtra(EXTRA_VIDEO_MIME) ?: MimeTypes.VIDEO_H265,
            outputHeight = intent.getIntExtra(EXTRA_OUTPUT_HEIGHT, 0),
            outputFps = intent.getIntExtra(EXTRA_OUTPUT_FPS, 0),
            originalWidth = intent.getIntExtra(EXTRA_ORIGINAL_WIDTH, 0),
            originalHeight = intent.getIntExtra(EXTRA_ORIGINAL_HEIGHT, 0),
            originalFps = intent.getFloatExtra(EXTRA_ORIGINAL_FPS, 30f),
            targetBitrate = intent.getIntExtra(EXTRA_TARGET_BITRATE, 2_000_000),
            audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000),
            audioCodec = intent.getStringExtra(EXTRA_AUDIO_CODEC) ?: MimeTypes.AUDIO_AAC,
            removeAudio = intent.getBooleanExtra(EXTRA_REMOVE_AUDIO, false),
            audioVolume = intent.getFloatExtra(EXTRA_AUDIO_VOLUME, 1f),
            onHdrToneMap = {
                BackgroundCompressionManager.setHdrWarning(getString(R.string.warning_hdr_tone_mapped))
            }
        )

        transformer = CompressionExecutor.execute(
            applicationContext,
            params,
            onCompleted = { finalSize ->
                val uri = Uri.fromFile(File(path))
                BackgroundCompressionManager.complete(uri, finalSize)
                finishCompression(getString(R.string.notification_bg_done))
            },
            onError = { exportException ->
                if (cancelled) {
                    finishCompression(null)
                    return@execute
                }
                val message = CompressionExecutor.errorMessage(applicationContext, exportException)
                BackgroundCompressionManager.fail(message, exportException.stackTraceToString())
                finishCompression(getString(R.string.notification_bg_failed))
            }
        )

        progressJob = scope.launch {
            while (transformer != null && BackgroundCompressionManager.state.value.isRunning) {
                val progressHolder = ProgressHolder()
                val status = transformer?.getProgress(progressHolder)
                if (status != null && status != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    val currentSize = if (File(path).exists()) File(path).length() else 0L
                    BackgroundCompressionManager.updateProgress(progressHolder.progress / 100f, currentSize)
                    val percent = progressHolder.progress
                    if (percent / 5 != lastProgressPercent) {
                        lastProgressPercent = percent / 5
                        updateNotification(percent)
                    }
                }
                delay(200)
            }
        }
    }

    private fun finishCompression(notificationText: String?) {
        progressJob?.cancel()
        progressJob = null
        transformer = null
        if (notificationText != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                showDoneLiveUpdate(notificationText)
            } else {
                showDoneNotification(notificationText)
            }
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(contentText: String, progressPercent: Int?): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildLiveUpdateNotification(contentText, progressPercent, contentIntent())
        } else {
            buildLegacyProgressNotification(contentText, progressPercent, contentIntent())
        }
    }

    private fun contentIntent(context: Context = this): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Android 16+ Live Updates: the platform [Notification.ProgressStyle] notification is
     * promoted to the lockscreen/status bar, and on Samsung One UI 8 it appears in the
     * Now Bar through the same Android 16 API. Promotion is requested via
     * the "android.requestPromotedOngoing" notification extra and the
     * POST_PROMOTED_NOTIFICATIONS manifest permission.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @SuppressLint("InlinedApi")
    private fun buildLiveUpdateNotification(
        contentText: String,
        progressPercent: Int?,
        contentIntent: PendingIntent
    ): Notification {
        val progressStyle = Notification.ProgressStyle()
        if (progressPercent != null) {
            progressStyle.setProgress(progressPercent)
            progressStyle.setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_notification))
        } else {
            progressStyle.setProgressIndeterminate(true)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_bg_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setExtras(liveUpdateExtras(contentText))
            .setShortCriticalText(contentText)
            .setStyle(progressStyle)
            .build()
    }

    /**
     * Completed-state live update: full progress bar, check tracker icon and "Done" text so the
     * live activity ends with a clear result instead of silently disappearing.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @SuppressLint("InlinedApi")
    private fun buildLiveUpdateDoneNotification(
        contentText: String,
        contentIntent: PendingIntent
    ): Notification {
        val progressStyle = Notification.ProgressStyle()
            .setProgress(100)
            .setStyledByProgress(false)
            .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_notification_done))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_bg_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setExtras(liveUpdateExtras(contentText))
            .setShortCriticalText(contentText)
            .setStyle(progressStyle)
            .build()
    }

    @SuppressLint("InlinedApi")
    private fun liveUpdateExtras(contentText: String): Bundle {
        return Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            samsungNowBarExtras(contentText)?.let { putAll(it) }
        }
    }

    private fun buildLegacyProgressNotification(
        contentText: String,
        progressPercent: Int?,
        contentIntent: PendingIntent
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_bg_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        samsungNowBarExtras(contentText)?.let { builder.setExtras(it) }
        return builder.build()
    }

    /**
     * Samsung extras: consumed by Samsung's SystemUI. One UI 7 Now Bar is whitelist-only; One UI
     * 8/8.5 surfaces the Android 16 Live Updates (see [buildLiveUpdateNotification]) in the Now Bar,
     * so these extras are included as a best effort on both notification paths.
     */
    private fun samsungNowBarExtras(contentText: String): Bundle? {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return null
        return Bundle().apply {
            putInt("android.ongoingActivityNoti.style", 1)
            putString("android.ongoingActivityNoti.primaryInfo", getString(R.string.notification_bg_title))
            putString("android.ongoingActivityNoti.secondaryInfo", contentText)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", getString(R.string.notification_bg_title))
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", contentText)
        }
    }

    private fun progressText(progressPercent: Int?): String =
        if (progressPercent != null) "$progressPercent%" else getString(R.string.notification_bg_starting)

    private fun updateNotification(percent: Int) {
        val notification = buildNotification(progressText(percent), percent)
        notifyCompressionNotification(notification)
    }

    private fun showDoneNotification(contentText: String) {
        notifyCompressionNotification(buildDoneNotification(this, contentText))
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun showDoneLiveUpdate(contentText: String) {
        notifyCompressionNotification(buildLiveUpdateDoneNotification(contentText, contentIntent()))
        replaceDoneLiveUpdateWhenAppForegrounds(contentText)
    }

    /**
     * Keeps the "Done" live update visible while the app is in the background and replaces it
     * with a regular dismissable notification once the app returns to the foreground.
     */
    private fun replaceDoneLiveUpdateWhenAppForegrounds(contentText: String) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            replaceDoneLiveUpdate(contentText)
            return
        }
        lateinit var observer: LifecycleEventObserver
        observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && !BackgroundCompressionManager.state.value.isRunning) {
                lifecycle.removeObserver(observer)
                replaceDoneLiveUpdate(contentText)
            }
        }
        lifecycle.addObserver(observer)
    }

    private fun replaceDoneLiveUpdate(contentText: String) {
        val context = applicationContext
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, buildDoneNotification(context, contentText))
        }
    }

    private fun buildDoneNotification(context: Context, contentText: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_bg_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyCompressionNotification(notification: Notification) {
        val notificationManager = NotificationManagerCompat.from(this)
        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startBatchCompression(intent: Intent) {
        val batch = BackgroundCompressionManager.pendingBatch
        if (batch.isEmpty()) {
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_bg_starting), null),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_bg_starting), null)
            )
        }

        progressJob = scope.launch(Dispatchers.IO) {
            var anyErrors = false
            var lastUri: Uri? = null
            val completedUris = mutableListOf<Uri>()

            for ((index, params) in batch.withIndex()) {
                if (!isActive || cancelled) break

                try {
                    val finalSize = CompressionExecutor.executeSuspend(applicationContext, params) { holder, _ ->
                        val overallProgress = (index.toFloat() + (holder.progress / 100f)) / batch.size.toFloat()
                        
                        val currentOutputSize = if (File(params.outputPath).exists()) File(params.outputPath).length() else 0L
                        BackgroundCompressionManager.updateProgress(overallProgress, currentOutputSize, params.inputUri, index)
                        
                        val currentPercent = (overallProgress * 100).toInt()
                        if (currentPercent != lastProgressPercent) {
                            lastProgressPercent = currentPercent
                            updateNotification(currentPercent)
                        }
                    }
                    
                    val uri = Uri.fromFile(File(params.outputPath))
                    lastUri = uri
                    completedUris.add(uri)
                } catch (e: Exception) {
                    val errorMsg = if (e is androidx.media3.transformer.ExportException) {
                        CompressionExecutor.errorMessage(applicationContext, e)
                    } else {
                        e.localizedMessage ?: getString(R.string.error_unknown)
                    }
                    if (e !is kotlinx.coroutines.CancellationException) {
                        BackgroundCompressionManager.fail(errorMsg, e.stackTraceToString())
                        updateNotification(0)
                    }
                    anyErrors = true
                    break
                }
            }

            if (!anyErrors && isActive && !cancelled) {
                val totalOutputSize = completedUris.sumOf { File(it.path!!).length() }
                BackgroundCompressionManager.complete(
                    lastUri ?: Uri.parse(""),
                    totalOutputSize,
                    completedUris
                )
                stopForeground(true)
                stopSelf()
            } else {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
