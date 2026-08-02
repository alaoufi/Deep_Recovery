package com.deeprecovery.pro.engine.scanner

import com.deeprecovery.pro.data.model.ScanStatus

/** لقطة حيّة عن حالة الفحص تُعرض في شاشة الفحص. */
data class ScanProgress(
    val sessionId: Long = -1L,
    val status: ScanStatus = ScanStatus.IDLE,
    val currentSource: String = "",
    val stageLabelRes: Int = 0,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val filesScanned: Int = 0,
    val filesFound: Int = 0,
    val imagesFound: Int = 0,
    val videosFound: Int = 0,
    val duplicatesFound: Int = 0,
    val foldersFound: Int = 0,
    val elapsedMs: Long = 0,
    val etaMs: Long = -1,
    val errorMessage: String? = null
) {
    /** نسبة الإنجاز 0..100. */
    val percent: Int
        get() = if (totalBytes <= 0) 0
        else ((processedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)

    val isActive: Boolean
        get() = status == ScanStatus.RUNNING || status == ScanStatus.PAUSED

    /**
     * لا نعرف حجم ما سنمرّ عليه مسبقاً في فحص المجلدات، فأي نسبة مئوية
     * هنا تخمين. في هذه الحالة نعرض مؤشراً حيّاً وعدّاد ملفات بدل نسبة
     * جامدة على الصفر توحي بأن التطبيق معلّق.
     */
    val isIndeterminate: Boolean
        get() = totalBytes <= 0
}
