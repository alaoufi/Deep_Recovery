package com.deeprecovery.pro.engine.carver

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.deeprecovery.pro.data.model.MediaType
import java.io.File

/**
 * التحقق الفعلي من صلاحية الملف المستخرج.
 *
 * الثقة البنيوية وحدها لا تكفي: نتأكد أن الصورة تُفكّ فعلاً وأن الفيديو
 * يعطي ميتاداتا صالحة. هذا ما يميّز "استعادة ممتازة" عن "بقايا ملف".
 */
object MediaValidator {

    data class Validation(
        val decodable: Boolean,
        val width: Int = 0,
        val height: Int = 0,
        val durationMs: Long = 0L
    )

    fun validate(file: File, mediaType: MediaType): Validation = when (mediaType) {
        MediaType.IMAGE -> validateImage(file)
        MediaType.VIDEO -> validateVideo(file)
        MediaType.UNKNOWN -> Validation(decodable = false)
    }

    private fun validateImage(file: File): Validation = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val ok = options.outWidth > 0 && options.outHeight > 0
        Validation(ok, options.outWidth.coerceAtLeast(0), options.outHeight.coerceAtLeast(0))
    } catch (e: Throwable) {
        Validation(decodable = false)
    }

    private fun validateVideo(file: File): Validation {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Validation(width > 0 && height > 0, width, height, duration)
        } catch (e: Throwable) {
            Validation(decodable = false)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * يدمج الثقة البنيوية مع نتيجة فك الترميز في درجة نهائية 0..100.
     *
     * - بنية كاملة + فك ترميز ناجح → استعادة ممتازة.
     * - فك ترميز ناجح مع بنية ناقصة → استعادة جيدة (الملف يُفتح لكنه مقطوع).
     * - فشل فك الترميز → استعادة ضعيفة مهما كانت البنية.
     */
    fun combineConfidence(
        structureConfidence: Int,
        truncated: Boolean,
        validation: Validation
    ): Int {
        var score = structureConfidence
        if (validation.decodable) {
            score = (score + 100) / 2
            if (truncated) score -= 15
        } else {
            score = (score * 0.35f).toInt()
        }
        return score.coerceIn(0, 100)
    }
}
