package com.deeprecovery.pro.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.R
import com.deeprecovery.pro.engine.RecoveryEngine

/**
 * ينفّذ الاستعادة في الخلفية.
 *
 * يدعم وضعين: استعادة ملفات محددة، أو استعادة **مجلد كامل بما فيه**
 * مع إعادة بناء شجرة المجلدات في الوجهة.
 */
class RecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        if (sessionId <= 0) return Result.failure()

        val destination = inputData.getString(KEY_DESTINATION)?.let(Uri::parse)
        val preserve = inputData.getBoolean(KEY_PRESERVE, true)
        val skipDuplicates = inputData.getBoolean(KEY_SKIP_DUPES, true)
        val folderPath = inputData.getString(KEY_FOLDER)
        val fileIds = inputData.getLongArray(KEY_FILE_IDS)?.toList().orEmpty()

        runCatching { setForeground(createForegroundInfo(0, 0)) }
        val engine = RecoveryEngine(applicationContext)
        var lastUpdate = 0L

        val report = if (folderPath != null) {
            engine.recoverFolder(sessionId, folderPath, destination, preserve, skipDuplicates) {
                // كبح تحديث الإشعار: نشره لكل ملف يضغط على النظام بلا داعٍ
                val now = System.currentTimeMillis()
                val isEdge = it.current <= 1 || it.current == it.total
                if (isEdge || now - lastUpdate >= NOTIFICATION_INTERVAL_MS) {
                    lastUpdate = now
                    runCatching { setForeground(createForegroundInfo(it.current, it.total)) }
                    runCatching {
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS_CURRENT to it.current,
                                KEY_PROGRESS_TOTAL to it.total,
                                KEY_PROGRESS_NAME to it.currentName,
                                KEY_PROGRESS_FOLDER to it.currentFolder
                            )
                        )
                    }
                }
            }
        } else {
            engine.recoverFiles(sessionId, fileIds, destination, preserve, skipDuplicates) {
                // كبح تحديث الإشعار: نشره لكل ملف يضغط على النظام بلا داعٍ
                val now = System.currentTimeMillis()
                val isEdge = it.current <= 1 || it.current == it.total
                if (isEdge || now - lastUpdate >= NOTIFICATION_INTERVAL_MS) {
                    lastUpdate = now
                    runCatching { setForeground(createForegroundInfo(it.current, it.total)) }
                    runCatching {
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS_CURRENT to it.current,
                                KEY_PROGRESS_TOTAL to it.total,
                                KEY_PROGRESS_NAME to it.currentName,
                                KEY_PROGRESS_FOLDER to it.currentFolder
                            )
                        )
                    }
                }
            }
        }

        return Result.success(
            workDataOf(
                KEY_RESULT_SUCCESS to report.succeeded,
                KEY_RESULT_FAILED to report.failed,
                KEY_RESULT_SKIPPED to report.skippedDuplicates,
                KEY_RESULT_FOLDERS to report.foldersCreated,
                KEY_RESULT_BYTES to report.bytesWritten,
                KEY_RESULT_RATE to report.successRate,
                KEY_RESULT_DESTINATION to report.destination,
                // أسباب الفشل كانت تُجمَع ولا تُعرض، فيبدو الفشل صامتاً
                KEY_RESULT_FAILURES to report.failures.take(5).toTypedArray(),
                KEY_RESULT_UNTRASH to report.needsUntrash.toTypedArray()
            )
        )
    }

    private fun createForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val context = applicationContext
        val notification: Notification = NotificationCompat
            .Builder(context, DeepRecoveryApp.CHANNEL_RECOVERY)
            .setContentTitle(context.getString(R.string.notif_recovery_title))
            .setContentText(context.getString(R.string.notif_recovery_text, current, total))
            .setSmallIcon(R.drawable.ic_notification_restore)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), current, total == 0)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "recovery"
        const val NOTIFICATION_ID = 4202
        private const val NOTIFICATION_INTERVAL_MS = 400L

        const val KEY_SESSION_ID = "session_id"
        const val KEY_DESTINATION = "destination"
        const val KEY_PRESERVE = "preserve"
        const val KEY_SKIP_DUPES = "skip_duplicates"
        const val KEY_FOLDER = "folder"
        const val KEY_FILE_IDS = "file_ids"

        const val KEY_PROGRESS_CURRENT = "current"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_PROGRESS_NAME = "name"
        const val KEY_PROGRESS_FOLDER = "folder"

        const val KEY_RESULT_SUCCESS = "succeeded"
        const val KEY_RESULT_FAILED = "failed"
        const val KEY_RESULT_SKIPPED = "skipped"
        const val KEY_RESULT_FOLDERS = "folders"
        const val KEY_RESULT_BYTES = "bytes"
        const val KEY_RESULT_RATE = "rate"
        const val KEY_RESULT_DESTINATION = "destination"
        const val KEY_RESULT_FAILURES = "failures"
        const val KEY_RESULT_UNTRASH = "untrash"

        fun inputForFolder(
            sessionId: Long,
            folderPath: String,
            destination: Uri?,
            preserve: Boolean,
            skipDuplicates: Boolean
        ): Data = workDataOf(
            KEY_SESSION_ID to sessionId,
            KEY_FOLDER to folderPath,
            KEY_DESTINATION to destination?.toString(),
            KEY_PRESERVE to preserve,
            KEY_SKIP_DUPES to skipDuplicates
        )

        fun inputForFiles(
            sessionId: Long,
            fileIds: List<Long>,
            destination: Uri?,
            preserve: Boolean,
            skipDuplicates: Boolean
        ): Data = workDataOf(
            KEY_SESSION_ID to sessionId,
            KEY_FILE_IDS to fileIds.toLongArray(),
            KEY_DESTINATION to destination?.toString(),
            KEY_PRESERVE to preserve,
            KEY_SKIP_DUPES to skipDuplicates
        )
    }
}
