package com.deeprecovery.pro.engine.scanner

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس على استبعاد ذواكر التطبيقات الحيّة.
 *
 * `WhatsApp/.Shared` وأمثاله مليئة بملفات مسمّاة ببصمة بلا امتداد، وهي
 * ملفات حيّة لا بقايا محذوفة. كان الفحص يقبلها جميعاً لأن «الامتداد لا
 * يطابق التوقيع»، فتُغرق النتائج بعشرات العناصر غير القابلة للاستعادة
 * ويُفتح كل ملف منها للقراءة — وهو سبب البطء المباشر.
 */
class AppCacheFilterTest {

    /** نفس العتبة المستخدمة في [FileSystemScanner] لقبول ملف بلا امتداد. */
    private val recoveryLocation = 30

    private fun isLiveAppCache(path: String) =
        CarvePriority.of(path) < recoveryLocation

    @Test
    fun whatsAppSharedCacheIsRejected() {
        val path = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/" +
            ".Shared/2008772d402c6cdfb3776f73960fa16f"
        assertTrue("ذاكرة WhatsApp المشتركة يجب ألا تُعتبر بقايا محذوفة", isLiveAppCache(path))
    }

    @Test
    fun telegramDocumentCacheIsRejected() {
        val path = "/storage/emulated/0/Android/data/org.telegram.messenger/files/" +
            "Telegram/documents/4f2a9c11d8"
        assertTrue("ذاكرة تيليجرام يجب ألا تُعتبر بقايا محذوفة", isLiveAppCache(path))
    }

    @Test
    fun genuineRemnantLocationsStillAccepted() {
        listOf(
            "/storage/emulated/0/DCIM/.thumbnails/.thumbdata3--1967290299",
            "/storage/emulated/0/LOST.DIR/00012",
            "/storage/emulated/0/.Trash/abcdef",
            "/storage/emulated/0/Android/data/x/cache/9f8e7d"
        ).forEach { path ->
            assertTrue("موقع بقايا حقيقي يجب أن يبقى مقبولاً: $path", !isLiveAppCache(path))
        }
    }
}
