package com.deeprecovery.pro.engine.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس على مؤشر الوقت المتبقي.
 *
 * ظل هذا المؤشر معطّلاً لأن مقام الحساب كان صفراً: لا عدد ملفات معروف
 * ولا حجم. الآن يُحسب من معدّل الملفات، وهذه الاختبارات تمنع عودته
 * إلى "جارٍ الحساب…" الدائمة.
 */
class ScanEtaTest {

    @Test
    fun estimatesFromFileRate() {
        // ١٠٠ ملف من أصل ٤٠٠ خلال ١٠ ثوانٍ ← ٣٠ ثانية متبقية
        val progress = ScanProgress(filesScanned = 100, totalFiles = 400)
        assertEquals(30_000L, progress.estimateEta(10_000))
    }

    @Test
    fun returnsZeroWhenAllFilesScanned() {
        val progress = ScanProgress(filesScanned = 400, totalFiles = 400)
        assertEquals(0L, progress.estimateEta(10_000))
    }

    @Test
    fun staysUnknownDuringTheFirstSecond() {
        // المعدّل في أول ثانية ضجيج يعطي أرقاماً متقلّبة
        val progress = ScanProgress(filesScanned = 5, totalFiles = 400)
        assertEquals(-1L, progress.estimateEta(200))
    }

    @Test
    fun fallsBackToByteRateForRawSectorScan() {
        // فحص القطاعات الخام: لا عدّ ملفات، لكن الحجم معروف
        val progress = ScanProgress(processedBytes = 250, totalBytes = 1_000)
        assertEquals(30_000L, progress.estimateEta(10_000))
    }

    @Test
    fun reportsUnknownWhenNothingIsMeasurable() {
        val progress = ScanProgress()
        assertEquals(-1L, progress.estimateEta(10_000))
    }

    @Test
    fun percentPrefersFileCountOverBytes() {
        val progress = ScanProgress(
            filesScanned = 50,
            totalFiles = 200,
            processedBytes = 900,
            totalBytes = 1_000
        )
        assertEquals(25, progress.percent)
        assertTrue(!progress.isIndeterminate)
    }

    @Test
    fun indeterminateOnlyWhenNoDenominatorExists() {
        assertTrue(ScanProgress().isIndeterminate)
        assertTrue(!ScanProgress(totalFiles = 10).isIndeterminate)
        assertTrue(!ScanProgress(totalBytes = 10).isIndeterminate)
    }
}
