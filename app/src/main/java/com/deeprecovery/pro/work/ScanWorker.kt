package com.deeprecovery.pro.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanLocation
import com.deeprecovery.pro.data.model.ScanMode
import com.deeprecovery.pro.engine.scanner.DeepScanEngine
import com.deeprecovery.pro.engine.scanner.ScanRequest
import com.deeprecovery.pro.ui.main.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * ينفّذ الفحص العميق في الخلفية عبر WorkManager كخدمة أمامية،
 * حتى لا يوقفه النظام أثناء العمليات الطويلة.
 *
 * محرك الفحص نفسه مشترك عبر [ScanController] ليتمكن المستخدم من
 * الإيقاف المؤقت والاستئناف من الواجهة.
 */
class ScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val request = ScanRequest(
            mode = ScanMode.fromName(inputData.getString(KEY_MODE)),
            depth = ScanDepth.fromName(inputData.getString(KEY_DEPTH)),
            locations = (inputData.getStringArray(KEY_LOCATIONS) ?: emptyArray())
                .mapNotNull { ScanLocation.fromKey(it) }
                .toSet(),
            includeImages = inputData.getBoolean(KEY_IMAGES, true),
            includeVideos = inputData.getBoolean(KEY_VIDEOS, true),
            detectDuplicates = inputData.getBoolean(KEY_DUPLICATES, true)
        )
        val resumeSessionId = inputData.getLong(KEY_SESSION_ID, -1L)

        val engine = ScanController.attach(applicationContext)
        setForeground(createForegroundInfo(0, 0))

        val reporter = launch {
            engine.progress.collectLatest { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_PERCENT to progress.percent,
                        KEY_PROGRESS_FILES to progress.filesFound,
                        KEY_SESSION_ID to progress.sessionId
                    )
                )
                runCatching {
                    setForeground(createForegroundInfo(progress.percent, progress.filesFound))
                }
            }
        }

        val sessionId = try {
            engine.runScan(request, resumeSessionId)
        } finally {
            reporter.cancel()
            ScanController.detach()
        }

        val prefs = (applicationContext as DeepRecoveryApp).repository.prefs
        prefs.lastSessionId = sessionId

        Result.success(workDataOf(KEY_SESSION_ID to sessionId))
    }

    private fun createForegroundInfo(percent: Int, files: Int): ForegroundInfo {
        val context = applicationContext
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat
            .Builder(context, DeepRecoveryApp.CHANNEL_SCAN)
            .setContentTitle(context.getString(R.string.notif_scan_title))
            .setContentText(context.getString(R.string.notif_scan_text, percent, files))
            .setSmallIcon(R.drawable.ic_scan)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "deep_scan"
        const val NOTIFICATION_ID = 4201

        const val KEY_MODE = "mode"
        const val KEY_DEPTH = "depth"
        const val KEY_LOCATIONS = "locations"
        const val KEY_IMAGES = "images"
        const val KEY_VIDEOS = "videos"
        const val KEY_DUPLICATES = "duplicates"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_PROGRESS_PERCENT = "percent"
        const val KEY_PROGRESS_FILES = "files"

        fun buildInput(request: ScanRequest, resumeSessionId: Long): Data = workDataOf(
            KEY_MODE to request.mode.name,
            KEY_DEPTH to request.depth.name,
            KEY_LOCATIONS to request.locations.map { it.key }.toTypedArray(),
            KEY_IMAGES to request.includeImages,
            KEY_VIDEOS to request.includeVideos,
            KEY_DUPLICATES to request.detectDuplicates,
            KEY_SESSION_ID to resumeSessionId
        )
    }
}

/**
 * يمنح الواجهة وصولاً إلى محرك الفحص الجاري لتنفيذ الإيقاف المؤقت
 * والاستئناف ومتابعة التقدّم الحيّ.
 */
object ScanController {

    @Volatile
    private var engine: DeepScanEngine? = null

    val current: DeepScanEngine? get() = engine

    fun attach(context: Context): DeepScanEngine =
        DeepScanEngine(context.applicationContext).also { engine = it }

    fun detach() {
        engine = null
    }

    fun pause() = engine?.pause()

    fun resume() = engine?.resume()

    val isPaused: Boolean get() = engine?.isPaused == true
}
