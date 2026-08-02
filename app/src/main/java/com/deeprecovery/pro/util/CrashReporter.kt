package com.deeprecovery.pro.util

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مسجّل الانهيارات.
 *
 * يلتقط أي استثناء غير معالَج ويكتبه في ملف داخل مساحة التطبيق، ثم يعرضه
 * المستخدم أو يشاركه. يعمل محلياً بالكامل — لا يُرسل شيء إلى أي خادم.
 *
 * كما يضع علامة "الجلسة السابقة انهارت" لكسر حلقة إعادة تشغيل الفحص
 * تلقائياً بعد الانهيار.
 */
object CrashReporter {

    private const val PREFS = "crash_reporter"
    private const val KEY_PENDING = "pending_crash"
    private const val KEY_CRASHED_DURING_SCAN = "crashed_during_scan"
    private const val LOG_FILE = "crash_log.txt"
    private const val MAX_LOG_BYTES = 256 * 1024

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("======== انهيار ========")
            appendLine("الوقت: $timestamp")
            appendLine("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("الخيط: ${thread.name}")
            appendLine(stack)
            appendLine()
        }

        val file = logFile(context)
        if (file.length() > MAX_LOG_BYTES) file.delete()
        file.appendText(report)

        prefs(context).edit { putBoolean(KEY_PENDING, true) }
    }

    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE)

    fun hasPendingReport(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PENDING, false) && logFile(context).exists()

    fun consumeReport(context: Context) {
        prefs(context).edit { putBoolean(KEY_PENDING, false) }
    }

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
        prefs(context).edit {
            putBoolean(KEY_PENDING, false)
            putBoolean(KEY_CRASHED_DURING_SCAN, false)
        }
    }

    fun readReport(context: Context): String =
        runCatching { logFile(context).readText() }.getOrDefault("")

    /**
     * يُعلَّم عند بدء الفحص ويُمسح عند انتهائه بسلام.
     *
     * إذا وُجدت العلامة عند الإقلاع فهذا يعني أن العملية ماتت أثناء الفحص،
     * فنلغي مهمة الفحص المعلّقة حتى لا يعيد WorkManager تشغيلها وينهار
     * التطبيق من جديد عند كل فتح.
     */
    fun markScanStarted(context: Context) {
        prefs(context).edit { putBoolean(KEY_CRASHED_DURING_SCAN, true) }
    }

    fun markScanFinished(context: Context) {
        prefs(context).edit { putBoolean(KEY_CRASHED_DURING_SCAN, false) }
    }

    fun didCrashDuringScan(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CRASHED_DURING_SCAN, false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
