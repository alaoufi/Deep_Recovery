package com.deeprecovery.pro.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس ضد فحص الملف نفسه أكثر من مرة.
 *
 * عند اختيار «الذاكرة الداخلية» مع DCIM و Camera و WhatsApp معاً تقع
 * الملفات تحت أكثر من جذر مختار، فتُفحص مراراً وتظهر مكررة في النتائج
 * ويتضاعف زمن الفحص.
 */
class StorageUtilsTest {

    @Test
    fun `يبقي الجذر الاعلى ويحذف الفروع المتداخلة`() {
        val result = StorageUtils.dedupeOverlappingPaths(
            listOf(
                "/storage/emulated/0/DCIM",
                "/storage/emulated/0",
                "/storage/emulated/0/DCIM/Camera",
                "/storage/emulated/0/Download"
            )
        )
        assertEquals(listOf("/storage/emulated/0"), result)
    }

    @Test
    fun `يبقي الجذور المستقلة كما هي`() {
        val result = StorageUtils.dedupeOverlappingPaths(
            listOf(
                "/storage/emulated/0/DCIM",
                "/storage/emulated/0/Download",
                "/storage/1234-5678/DCIM"
            )
        )
        assertEquals(3, result.size)
    }

    @Test
    fun `لا يخلط بين مجلدين يتشابه اسمهما في البداية`() {
        val result = StorageUtils.dedupeOverlappingPaths(
            listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/DCIM2")
        )
        assertEquals(2, result.size)
    }

    @Test
    fun hotspotsCoverWhereDeletedDataLives() {
        val paths = StorageUtils.hotspotPaths("/storage/emulated/0")

        // ذاكرة المصغّرات: أعلى مصدر استعادة بلا Root
        assertTrue(paths.any { it.endsWith("/DCIM/.thumbnails") })
        // سلال المهملات و LOST.DIR
        assertTrue(paths.any { it.endsWith("/LOST.DIR") })
        assertTrue(paths.any { it.endsWith("/.Trash") })
        assertTrue(paths.any { it.endsWith("/DCIM/.Trash") })
        // مجلدات الوسائط نفسها لالتقاط ملفات trashed.
        assertTrue(paths.any { it.endsWith("/DCIM") })
        assertTrue(paths.any { it.endsWith("/Pictures") })
        assertTrue(paths.any { it.endsWith("/Movies") })
        // وسائط التطبيقات
        assertTrue(paths.any { it.contains("WhatsApp") })
        assertTrue(paths.any { it.contains("Telegram") })
    }

    @Test
    fun hotspotsExcludeUnrelatedAppData() {
        val paths = StorageUtils.hotspotPaths("/storage/emulated/0")

        // المرور على /Android/data كاملاً هو ما جعل الفحص يستغرق
        // عشرات الدقائق على عشرات آلاف الملفات بلا فائدة
        assertTrue(paths.none { it.endsWith("/Android") })
        assertTrue(paths.none { it.endsWith("/Android/data") })
        // ولا الجذر نفسه
        assertTrue(paths.none { it.trimEnd('/') == "/storage/emulated/0" })
    }

    @Test
    fun `يتجاهل الشرطة الاخيرة والتكرار`() {
        val result = StorageUtils.dedupeOverlappingPaths(
            listOf("/storage/emulated/0/DCIM/", "/storage/emulated/0/DCIM")
        )
        assertEquals(listOf("/storage/emulated/0/DCIM"), result)
    }
}
