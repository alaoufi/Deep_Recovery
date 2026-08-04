package com.deeprecovery.pro

import com.deeprecovery.pro.util.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اسم الألبوم يصبح مجلداً حقيقياً في التخزين، فأي محرف ممنوع فيه
 * يُفشل إنشاء الملف بصمت — وهذا بالضبط ما كان يجعل الاستعادة تبدو ناجحة
 * بلا ملفات.
 */
class AlbumNameTest {

    @Test
    fun keepsPlainName() {
        assertEquals("صور العائلة", AppPrefs.sanitizeAlbum("  صور العائلة  "))
    }

    @Test
    fun replacesPathSeparators() {
        assertEquals("a_b_c", AppPrefs.sanitizeAlbum("a/b\\c"))
    }

    @Test
    fun replacesReservedCharacters() {
        assertEquals("my_album_", AppPrefs.sanitizeAlbum("my:album?"))
    }

    @Test
    fun fallsBackWhenBlank() {
        assertEquals(AppPrefs.DEFAULT_ALBUM, AppPrefs.sanitizeAlbum("   "))
    }

    @Test
    fun fallsBackWhenOnlyDots() {
        assertEquals(AppPrefs.DEFAULT_ALBUM, AppPrefs.sanitizeAlbum(".."))
    }

    @Test
    fun trimsOverlongNames() {
        assertEquals(60, AppPrefs.sanitizeAlbum("x".repeat(200)).length)
    }
}
