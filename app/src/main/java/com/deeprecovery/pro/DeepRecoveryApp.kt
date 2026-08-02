package com.deeprecovery.pro

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.WorkManager
import com.deeprecovery.pro.data.repository.RecoveryRepository
import com.deeprecovery.pro.util.CrashReporter
import com.deeprecovery.pro.work.ScanWorker

class DeepRecoveryApp : Application() {

    val repository: RecoveryRepository by lazy { RecoveryRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        createNotificationChannels()
        breakScanCrashLoop()
    }

    /**
     * إذا ماتت العملية أثناء فحص سابق، فمهمة الفحص تبقى مسجّلة في
     * WorkManager ويُعاد تشغيلها تلقائياً عند فتح التطبيق — فينهار مجدداً.
     * نكسر هذه الحلقة بإلغاء المهمة المعلّقة عند الإقلاع.
     */
    private fun breakScanCrashLoop() {
        if (!CrashReporter.didCrashDuringScan(this)) return
        runCatching {
            WorkManager.getInstance(this).cancelUniqueWork(ScanWorker.WORK_NAME)
        }
        CrashReporter.markScanFinished(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCAN,
                getString(R.string.channel_scan),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_scan_desc) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECOVERY,
                getString(R.string.channel_recovery),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_recovery_desc) }
        )
    }

    companion object {
        const val CHANNEL_SCAN = "scan_progress"
        const val CHANNEL_RECOVERY = "recovery_progress"

        lateinit var instance: DeepRecoveryApp
            private set
    }
}
