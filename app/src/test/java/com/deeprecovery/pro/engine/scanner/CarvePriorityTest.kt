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
