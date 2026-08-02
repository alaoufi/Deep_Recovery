package com.deeprecovery.pro.util

import org.junit.Assert.assertEquals
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
    fun `يتجاهل الشرطة الاخيرة والتكرار`() {
        val result = StorageUtils.dedupeOverlappingPaths(
            listOf("/storage/emulated/0/DCIM/", "/storage/emulated/0/DCIM")
        )
        assertEquals(listOf("/storage/emulated/0/DCIM"), result)
    }
}
