package com.deeprecovery.pro

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * حارس ضد الانهيار الذي كان يقتل التطبيق عند بدء الفحص على Android 12.
 *
 * نمرّر `FOREGROUND_SERVICE_TYPE_DATA_SYNC` في [androidx.work.ForegroundInfo]،
 * لكن خدمة WorkManager الأمامية مُعلَنة في بيان المكتبة بلا
 * `foregroundServiceType`. عندها يرفض النظام التشغيل ويرمي:
 *
 * > foregroundServiceType 0x00000001 is not a subset of
 * > foregroundServiceType attribute 0x00000000 in service element of manifest
 *
 * والاستثناء يُرمى على الخيط الرئيسي بشكل غير متزامن، فلا ينفع التقاطه
 * حول استدعاء `setForeground`. الحل الوحيد هو دمج النوع في بيان التطبيق.
 */
// ملاحظة: أسماء الدوال هنا بالإنجليزية عمداً — الاسم العربي مع تعبير
// lambda غير مضمّن يولّد ملف class باسم عربي، وبعض بيئات البناء لا تقبل
// هذا الترميز في مسارات الملفات. الرسائل تبقى بالعربية.
class ForegroundServiceTypeTest {

    private val moduleDir: File = findModuleDir()

    private fun findModuleDir(): File {
        var current: File? = File(".").absoluteFile.normalize()
        while (current != null) {
            if (File(current, "src/main/AndroidManifest.xml").isFile) return current
            if (File(current, "app/src/main/AndroidManifest.xml").isFile) {
                return File(current, "app")
            }
            current = current.parentFile
        }
        error("تعذّر تحديد مجلد الوحدة انطلاقاً من ${File(".").absolutePath}")
    }

    @Test
    fun manifestMergesForegroundServiceTypeForWorkManager() {
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml").readText()
        val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val markup = comments.replace(manifest, "")

        val service = Regex(
            """<service\b[^>]*androidx\.work\.impl\.foreground\.SystemForegroundService[^>]*/>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(markup)

        assertTrue(
            "بيان التطبيق لا يُعلن SystemForegroundService — سينهار الفحص على Android 12+",
            service != null
        )
        assertTrue(
            "إعلان SystemForegroundService ينقصه foregroundServiceType=\"dataSync\"",
            service!!.value.contains("android:foregroundServiceType=\"dataSync\"")
        )
    }

    /**
     * البيان المصدري وحده لا يثبت نجاح الدمج، لذلك نتحقق من البيان المدمَج
     * فعلياً متى كان متوفراً من بناء سابق.
     */
    @Test
    fun everyMergedManifestCarriesTheServiceType() {
        val mergedDir = File(moduleDir, "build/intermediates/merged_manifest")
        if (!mergedDir.isDirectory) return // لم يُنفَّذ بناء بعد — يغطيه الاختبار الأعلى

        val manifests = mergedDir.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" }
            .toList()
        if (manifests.isEmpty()) return

        val declaration = Regex(
            """<service[^>]*androidx\.work\.impl\.foreground\.SystemForegroundService[^>]*>""",
            RegexOption.DOT_MATCHES_ALL
        )

        manifests.forEach { manifest ->
            val found = declaration.find(manifest.readText())
            assertTrue(
                "${manifest.parentFile.name}: البيان المدمَج لا يحتوي إعلان SystemForegroundService",
                found != null
            )
            assertTrue(
                "${manifest.parentFile.name}: ينقصه foregroundServiceType=\"dataSync\" — الدمج لم ينجح",
                found!!.value.contains("android:foregroundServiceType=\"dataSync\"")
            )
        }
    }
}
