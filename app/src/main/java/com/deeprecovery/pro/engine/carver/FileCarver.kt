package com.deeprecovery.pro.engine.carver

import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.util.HashUtils
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/** ملف مُستخرَج فعلياً من البيانات الخام. */
data class CarvedFile(
    val stagedFile: File,
    val signatureId: String,
    val extension: String,
    val mimeType: String,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val sourceOffset: Long,
    val sourceName: String,
    val confidence: Int,
    val quality: RecoveryQuality,
    val truncated: Boolean,
    val note: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val contentHash: String
)

/**
 * منسّق عملية File Carving.
 *
 * يقرأ المصدر الخام على شكل قطع متداخلة، يبحث عن التواقيع داخل كل قطعة،
 * ثم يستدعي المحرك المناسب لتحديد نهاية الملف، ويكتب الناتج في مساحة
 * مؤقتة داخل التطبيق قبل عرضه للمستخدم.
 *
 * التداخل بين القطع يضمن عدم ضياع أي توقيع يقع على حدود قطعة القراءة.
 */
class FileCarver(
    private val stagingDir: File,
    private val signatures: List<FileSignature>,
    private val minFreeBytes: Long = 200L * 1024 * 1024
) {

    companion object {
        /**
         * حجم قطعة القراءة. أبقيناه معتدلاً عمداً: القطع الكبيرة مع الصور
         * المصغّرة وذاكرة Room تدفع الأجهزة محدودة الذاكرة إلى OutOfMemory.
         */
        const val CHUNK_SIZE = 2 * 1024 * 1024
        private const val COPY_BUFFER = 128 * 1024

        private val carvers: Map<String, Carver> = listOf(
            JpegCarver, PngCarver, RiffCarver, IsoBmffCarver, MatroskaCarver
        ).associateBy { it.id }
    }

    private val overlap = SignatureRegistry.maxSignatureSpan.coerceAtLeast(16)

    /** إحصاءات مجمّعة لعملية النحت. */
    class Stats {
        var candidates: Long = 0
        var extracted: Long = 0
        var rejected: Long = 0
        var bytesRead: Long = 0
        var skippedNoSpace: Long = 0
    }

    /**
     * يفحص [source] بدءاً من [startOffset] ويستدعي [onFile] لكل ملف مستخرج.
     *
     * @param onProgress يُستدعى مع عدد البايتات المعالَجة تراكمياً، ويُستخدم
     *   أيضاً كنقطة تحقق للإيقاف المؤقت والإلغاء.
     */
    suspend fun carve(
        source: RawSource,
        startOffset: Long = 0L,
        stats: Stats = Stats(),
        skipSelfAtOffsetZero: Boolean = false,
        onProgress: suspend (processedBytes: Long, absolutePosition: Long) -> Unit,
        onFile: suspend (CarvedFile) -> Unit
    ): Stats {
        val buffer = ByteArray(CHUNK_SIZE)
        var position = startOffset
        val total = source.length
        // لا نبحث داخل ملف سبق استخراجه بالكامل
        var skipUntil = startOffset

        while (total <= 0 || position < total) {
            coroutineContext.ensureActive()

            val read = source.readFully(position, buffer, 0, CHUNK_SIZE)
            if (read <= 0) break
            stats.bytesRead += read

            var i = 0
            while (i < read) {
                coroutineContext.ensureActive()
                val absolute = position + i
                if (absolute < skipUntil) {
                    val jump = (skipUntil - position).coerceAtMost(read.toLong()).toInt()
                    i = jump.coerceAtLeast(i + 1)
                    continue
                }

                val signature = signatures.firstOrNull { it.matchesAt(buffer, i, read) }
                if (signature == null) {
                    i++
                    continue
                }

                // عند النحت داخل ملف قائم، التوقيع عند الإزاحة 0 هو الملف
                // نفسه وليس بقايا ملف محذوف — نتخطاه حتى لا ننسخ كل صور
                // الجهاز إلى مساحة العمل ونملأ التخزين.
                if (skipSelfAtOffsetZero && absolute == 0L) {
                    i++
                    continue
                }

                stats.candidates++
                val carved = tryExtract(source, absolute, signature, stats)
                if (carved != null) {
                    stats.extracted++
                    skipUntil = absolute + carved.sizeBytes
                    onFile(carved)
                } else {
                    stats.rejected++
                    i++
                }
            }

            onProgress(position + read - startOffset, position + read)

            if (read < CHUNK_SIZE) break
            position += (CHUNK_SIZE - overlap)
        }

        val processed = if (total > 0) total - startOffset else stats.bytesRead
        onProgress(processed, if (total > 0) total else position)
        return stats
    }

    /** يحاول استخراج ملف واحد؛ يعيد null إذا كانت البنية غير صالحة أو المساحة غير كافية. */
    private fun tryExtract(
        source: RawSource,
        offset: Long,
        signature: FileSignature,
        stats: Stats
    ): CarvedFile? {
        val carver = carvers[signature.carverId] ?: return null
        val extent = runCatching { carver.determineExtent(source, offset, signature) }
            .getOrNull() ?: return null

        if (extent.length < signature.minSize || extent.length > signature.maxSize) return null
        if (stagingDir.usableSpace < minFreeBytes + extent.length) {
            stats.skippedNoSpace++
            return null
        }

        val target = File(stagingDir, carvedFileName(signature, offset))
        val written = runCatching { copyRange(source, offset, extent.length, target) }
            .getOrElse {
                target.delete()
                return null
            }
        if (written < signature.minSize) {
            target.delete()
            return null
        }

        val validation = MediaValidator.validate(
            target,
            signature.mediaType,
            extent.structureConfidence
        )

        // نتجاهل البقايا التالفة تماماً: لا تُفكّ ولا تملك بنية معقولة
        if (!validation.decodable && extent.structureConfidence < 30) {
            target.delete()
            return null
        }

        // صوت داخل حاوية MP4 ليس مقطع فيديو: يُعرض بمدة صحيحة وبلا صورة
        // ثم يخرج صوتاً بلا مشهد عند التشغيل
        if (validation.audioOnly) {
            target.delete()
            return null
        }

        val confidence = MediaValidator.combineConfidence(
            extent.structureConfidence,
            extent.truncated,
            validation
        )

        return CarvedFile(
            stagedFile = target,
            signatureId = signature.id,
            extension = signature.extension,
            mimeType = signature.mimeType,
            mediaType = signature.mediaType,
            sizeBytes = written,
            sourceOffset = offset,
            sourceName = source.displayName,
            confidence = confidence,
            quality = RecoveryQuality.fromConfidence(confidence),
            truncated = extent.truncated,
            note = extent.note,
            width = validation.width,
            height = validation.height,
            durationMs = validation.durationMs,
            contentHash = HashUtils.contentHash(target)
        )
    }

    private fun carvedFileName(signature: FileSignature, offset: Long): String =
        "carved_${signature.id}_${offset}.${signature.extension}"

    private fun copyRange(source: RawSource, offset: Long, length: Long, target: File): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = length
        var position = offset
        var written = 0L
        FileOutputStream(target).use { out ->
            while (remaining > 0) {
                val toRead = minOf(remaining, COPY_BUFFER.toLong()).toInt()
                val read = source.readFully(position, buffer, 0, toRead)
                if (read <= 0) break
                out.write(buffer, 0, read)
                written += read
                position += read
                remaining -= read
            }
            out.flush()
        }
        return written
    }
}
