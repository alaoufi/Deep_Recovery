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
    /** إجمالي الملفات المتوقع فحصها، أو 0 إن تعذّر حسابه. */
    val totalFiles: Int = 0,
    val filesFound: Int = 0,
    val imagesFound: Int = 0,
    val videosFound: Int = 0,
    val duplicatesFound: Int = 0,
    val foldersFound: Int = 0,
    val elapsedMs: Long = 0,
    val etaMs: Long = -1,
    val errorMessage: String? = null
) {
    /**
     * نسبة الإنجاز 0..100.
     *
     * تُحسب بعدد الملفات في فحص المجلدات، وبالبايتات في فحص القطاعات
     * الخام حيث الحجم معروف مسبقاً.
     */
    val percent: Int
        get() = when {
            totalFiles > 0 ->
                ((filesScanned.toDouble() / totalFiles) * 100).toInt().coerceIn(0, 100)
            totalBytes > 0 ->
                ((processedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
            else -> 0
        }

    val isActive: Boolean
        get() = status == ScanStatus.RUNNING || status == ScanStatus.PAUSED

    /**
     * لا نعرف حجم ما سنمرّ عليه مسبقاً في فحص المجلدات، فأي نسبة مئوية
     * هنا تخمين. في هذه الحالة نعرض مؤشراً حيّاً وعدّاد ملفات بدل نسبة
     * جامدة على الصفر توحي بأن التطبيق معلّق.
     */
    val isIndeterminate: Boolean
        get() = totalFiles <= 0 && totalBytes <= 0

    /**
     * الوقت المتبقي بالمللي ثانية، أو -1 إذا تعذّر تقديره بعد.
     *
     * يُحسب من معدّل الملفات في الثانية حين يكون عددها الكلي معروفاً —
     * وهو الحال الغالب في الفحص الموجّه — ومن معدّل البايتات في فحص
     * القطاعات الخام حيث الحجم معروف مسبقاً.
     *
     * لا نقدّر شيئاً في أول ثانية: المعدّل حينها ضجيج يعطي أرقاماً
     * سخيفة تتقلّب أمام المستخدم.
     */
    fun estimateEta(elapsedMs: Long): Long {
        if (elapsedMs < 1_000) return -1L

        if (totalFiles > 0 && filesScanned > 0) {
            if (filesScanned >= totalFiles) return 0L
            val perMs = filesScanned.toDouble() / elapsedMs
            if (perMs <= 0.0) return -1L
            return ((totalFiles - filesScanned) / perMs).toLong()
        }

        if (processedBytes <= 0 || totalBytes <= processedBytes) return -1L
        val rate = processedBytes.toDouble() / elapsedMs
        if (rate <= 0.0) return -1L
        return ((totalBytes - processedBytes) / rate).toLong()
    }
}
