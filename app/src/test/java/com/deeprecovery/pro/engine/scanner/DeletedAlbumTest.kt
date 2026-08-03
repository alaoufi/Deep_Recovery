package com.deeprecovery.pro.engine.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * حارس على توجيه الفحص عند اختيار ألبوم محذوف.
 *
 * إن كان مجلد الألبوم نفسه قد حُذف فلا فائدة من توجيه الفحص إلى مسار لم
 * يعد موجوداً؛ نوجّهه إلى المجلد الأب حيث قد تبقى بقايا، مع ذاكرة
 * المصغّرات وسلة المهملات.
 */
class DeletedAlbumTest {

    @Test
    fun existingAlbumIsScannedInPlace() {
        val album = DeletedAlbum(
            path = "/storage/emulated/0/DCIM/Trip",
            name = "Trip",
            missingCount = 12,
            existsOnDisk = true
        )
        assertEquals("/storage/emulated/0/DCIM/Trip", album.scanTarget)
    }

    @Test
    fun deletedAlbumFallsBackToParentFolder() {
        val album = DeletedAlbum(
            path = "/storage/emulated/0/DCIM/Trip",
            name = "Trip",
            missingCount = 12,
            existsOnDisk = false
        )
        assertEquals("/storage/emulated/0/DCIM", album.scanTarget)
    }

    @Test
    fun albumWithoutParentKeepsItsOwnPath() {
        val album = DeletedAlbum(
            path = "Trip",
            name = "Trip",
            missingCount = 1,
            existsOnDisk = false
        )
        assertEquals("Trip", album.scanTarget)
    }
}
