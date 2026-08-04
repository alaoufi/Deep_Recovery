package com.deeprecovery.pro.engine.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس على ترتيب مرشّحي النحت.
 *
 * ذاكرة المصغّرات `.thumbdata` هي أعلى مصدر إنتاجية للاستعادة بلا Root،
 * فيجب ألا يُستهلك سقف المرشّحين على الصور العادية السليمة قبل الوصول
 * إليها.
 */
// أسماء الدوال بالإنجليزية عمداً: الاسم العربي مع lambda غير مضمّن
// يولّد ملف class باسم عربي وبعض بيئات البناء لا تقبله في المسارات.
class CarvePriorityTest {

    @Test
    fun thumbnailCacheOutranksOrdinaryPhotos() {
        val ordered = listOf(
            "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
            "/storage/emulated/0/DCIM/.thumbnails/.thumbdata4--1967290299",
            "/storage/emulated/0/Download/movie.mp4"
        ).sortedByDescending { CarvePriority.of(it) }

        assertTrue(ordered.first().contains(".thumbdata"))
    }

    @Test
    fun trashFoldersOutrankOrdinaryFiles() {
        val ordinary = CarvePriority.of("/storage/emulated/0/DCIM/Camera/IMG.jpg")
        assertTrue(CarvePriority.of("/storage/emulated/0/LOST.DIR/1234") > ordinary)
        assertTrue(CarvePriority.of("/storage/emulated/0/.Trash/x") > ordinary)
        assertTrue(CarvePriority.of("/a/b/.trashed-1000-IMG.jpg") > ordinary)
        assertTrue(CarvePriority.of("/a/b/.thumbnails/x.jpg") > ordinary)
    }

    @Test
    fun ordinaryFileKeepsLowestPriority() {
        assertEquals(10, CarvePriority.of("/storage/emulated/0/DCIM/Camera/IMG_0001.jpg"))
    }

    @Test
    fun priorityIsCaseInsensitive() {
        assertEquals(
            CarvePriority.of("/storage/emulated/0/LOST.DIR/1"),
            CarvePriority.of("/storage/emulated/0/lost.dir/1")
        )
    }
}

/**
 * حارس على عتبة قبول النحت.
 *
 * كان الشرط الوحيد هو الحجم، فدخلت كل صورة سليمة أكبر من ميغابايت في
 * قائمة النحت — عشرات الآلاف من الملفات، ونحتها يعيد استخراج ما هو
 * موجود أصلاً. العتبة تفصل بقايا المحذوف عن الملفات العادية.
 */
class CarveThresholdTest {

    /** نفس العتبة المستخدمة في محرك الفحص للأعماق غير الكاملة. */
    private val threshold = 30

    @Test
    fun ordinaryPhotosFallBelowThreshold() {
        listOf(
            "/storage/emulated/0/DCIM/Camera/IMG_20240101_120000.jpg",
            "/storage/emulated/0/Pictures/Screenshots/Screenshot_1.png",
            "/storage/emulated/0/Movies/VID_20240101.mp4",
            "/storage/emulated/0/Download/report.pdf"
        ).forEach { path ->
            assertTrue(
                "ملف عادي يجب ألا يدخل النحت: $path",
                CarvePriority.of(path) < threshold
            )
        }
    }

    @Test
    fun recoverySourcesReachThreshold() {
        listOf(
            "/storage/emulated/0/DCIM/.thumbnails/.thumbdata3--1967290299",
            "/storage/emulated/0/DCIM/.thumbnails/1503407997846.jpg",
            "/storage/emulated/0/LOST.DIR/12345.jpg",
            "/storage/emulated/0/.Trash/old.jpg",
            "/storage/emulated/0/DCIM/Camera/.trashed-1699999999-IMG_1.jpg",
            "/storage/emulated/0/Android/data/x/cache/blob.bin",
            "/storage/emulated/0/DCIM/.hidden_remnant"
        ).forEach { path ->
            assertTrue(
                "مصدر بقايا يجب أن يدخل النحت: $path",
                CarvePriority.of(path) >= threshold
            )
        }
    }

    @Test
    fun thresholdSitsAboveOrdinaryScore() {
        val ordinary = CarvePriority.of("/storage/emulated/0/DCIM/Camera/IMG_1.jpg")
        assertEquals(10, ordinary)
        assertTrue("العتبة يجب أن تستبعد درجة الملف العادي", threshold > ordinary)
    }
}
