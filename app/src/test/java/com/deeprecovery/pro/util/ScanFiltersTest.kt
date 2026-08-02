package com.deeprecovery.pro.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس على استثناء المجلدات واستهداف المجلد الخاص.
 *
 * الاستثناء يقارن اسم المجلد بحروف صغيرة، فأي اسم في القوائم يجب أن
 * يكون بحروف صغيرة وإلا لن يُطابق شيئاً أبداً.
 */
class ScanFiltersTest {

    @Test
    fun exclusionNamesAreLowercaseSoMatchingWorks() {
        val all = StorageUtils.SCREENSHOT_DIR_NAMES +
            StorageUtils.WHATSAPP_IMAGE_DIR_NAMES +
            StorageUtils.WHATSAPP_VIDEO_DIR_NAMES
        assertTrue(all.all { it == it.lowercase() })
    }

    @Test
    fun whatsAppMediaFoldersAreRecognised() {
        assertTrue("WhatsApp Images".lowercase() in StorageUtils.WHATSAPP_IMAGE_DIR_NAMES)
        assertTrue("WhatsApp Video".lowercase() in StorageUtils.WHATSAPP_VIDEO_DIR_NAMES)
        // الصور والفيديو مستقلان: استثناء أحدهما لا يستثني الآخر
        assertFalse("WhatsApp Images".lowercase() in StorageUtils.WHATSAPP_VIDEO_DIR_NAMES)
        assertFalse("WhatsApp Video".lowercase() in StorageUtils.WHATSAPP_IMAGE_DIR_NAMES)
    }

    @Test
    fun screenshotsExclusionDoesNotCatchWhatsAppMedia() {
        assertFalse("whatsapp images" in StorageUtils.SCREENSHOT_DIR_NAMES)
        assertFalse("whatsapp video" in StorageUtils.SCREENSHOT_DIR_NAMES)
    }

    @Test
    fun privateFoldersAreTargetedByHotspotScan() {
        val paths = StorageUtils.hotspotPaths("/storage/emulated/0")
        // خزنة كل مصنّع لها مسارها، ولا يصلها الفحص ما لم تُستهدف صراحةً
        assertTrue(paths.any { it.endsWith("/.privateProtect") })
        assertTrue(paths.any { it.endsWith("/.safebox") })
        assertTrue(paths.any { it.endsWith("/MIUI/privacy") })
        assertTrue(paths.any { it.endsWith("/.vivo_hide") })
        assertTrue(paths.any { it.endsWith("/Private") })
    }
}
