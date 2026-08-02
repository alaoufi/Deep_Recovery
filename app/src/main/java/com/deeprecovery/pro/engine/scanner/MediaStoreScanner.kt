package com.deeprecovery.pro.engine.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import java.io.File

/** ملف اكتُشف عبر MediaStore أو عبر المرور على المجلدات. */
data class DiscoveredFile(
    val displayName: String,
    val path: String?,
    val uri: String?,
    val mimeType: String,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val folderPath: String,
    val folderLabel: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val confidence: Int,
    val quality: RecoveryQuality,
    val note: String,
    /** تاريخ إنشاء الملف الأصلي بالمللي ثانية. */
    val createdAt: Long = 0
)

/**
 * تحليل **MediaStore** في الوضع العادي (بدون Root).
 *
 * يستهدف ما يمكن الوصول إليه فعلاً بلا صلاحيات جذر:
 *  - سلة المهملات (`MediaStore.Files.FileColumns.IS_TRASHED`) على Android 11+.
 *  - العناصر المعلّقة (`IS_PENDING`) التي بقيت من عمليات كتابة لم تكتمل.
 *  - المدخلات التي لم يعد لها ملف على القرص (مؤشر على حذف حديث).
 */
class MediaStoreScanner(private val context: Context) {

    /** يبحث في سلة المهملات والعناصر المعلّقة. */
    fun scanTrashedAndPending(
        includeImages: Boolean,
        includeVideos: Boolean
    ): List<DiscoveredFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val results = mutableListOf<DiscoveredFile>()
        if (includeImages) results += queryTrashed(MediaType.IMAGE)
        if (includeVideos) results += queryTrashed(MediaType.VIDEO)
        return results
    }

    private fun queryTrashed(mediaType: MediaType): List<DiscoveredFile> {
        val collection = when (mediaType) {
            MediaType.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_ADDED
        )

        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_TRASHED} = 1 OR ${MediaStore.MediaColumns.IS_PENDING} = 1"
            )
        }

        val results = mutableListOf<DiscoveredFile>()
        runCatching {
            context.contentResolver.query(collection, projection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val trashedCol = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = cursor.getString(nameCol) ?: "media_$id"
                    val relative = relCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
                        ?.trimEnd('/')
                        ?: "MediaStore"
                    val trashed = trashedCol >= 0 && cursor.getInt(trashedCol) == 1

                    results += DiscoveredFile(
                        displayName = name,
                        path = dataCol.takeIf { it >= 0 }?.let { cursor.getString(it) },
                        uri = uri.toString(),
                        mimeType = mimeCol.takeIf { it >= 0 }?.let { cursor.getString(it) }
                            ?: if (mediaType == MediaType.IMAGE) "image/*" else "video/*",
                        mediaType = mediaType,
                        sizeBytes = sizeCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        folderPath = relative,
                        folderLabel = relative.substringAfterLast('/').ifEmpty { relative },
                        width = widthCol.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                        height = heightCol.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                        durationMs = durationCol.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        confidence = if (trashed) 95 else 80,
                        quality = if (trashed) RecoveryQuality.EXCELLENT else RecoveryQuality.GOOD,
                        note = if (trashed) "trashed" else "pending",
                        // DATE_ADDED بالثواني
                        createdAt = dateCol.takeIf { it >= 0 }
                            ?.let { cursor.getLong(it) * 1000L } ?: 0L
                    )
                }
            }
        }
        return results
    }

    /**
     * مدخلات MediaStore التي فقدت ملفها على القرص — دليل على حذف حديث،
     * وتفيد في توجيه الفحص العميق إلى المجلدات الصحيحة.
     */
    fun findOrphanEntries(includeImages: Boolean, includeVideos: Boolean): List<String> {
        val folders = mutableSetOf<String>()
        val collections = buildList {
            if (includeImages) add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (includeVideos) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        collections.forEach { uri ->
            runCatching {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataCol < 0) return@use
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol) ?: continue
                        val file = File(path)
                        if (!file.exists()) {
                            file.parent?.let { folders += it }
                        }
                    }
                }
            }
        }
        return folders.toList()
    }
}
