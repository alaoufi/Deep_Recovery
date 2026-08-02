package com.deeprecovery.pro.engine.carver

/**
 * محرك استخراج **MKV** (Matroska / WebM).
 *
 * البنية EBML: كل عنصر = `ID (vint مع بايتات العلامة)` + `Size (vint بدون العلامة)`.
 * نقرأ ترويسة EBML ثم عنصر Segment؛ إذا كان حجم Segment معروفاً نحسب
 * النهاية مباشرة، وإذا كان غير معروف (كل البتات 1) نمشي على العناصر
 * العليا (Cluster, Tracks, Cues ...) حتى تنكسر البنية.
 */
object MatroskaCarver : Carver {

    const val ID = "carver_mkv"
    override val id: String = ID

    private const val EBML_HEADER_ID = 0x1A45DFA3L
    private const val SEGMENT_ID = 0x18538067L

    private val TOP_LEVEL_IDS = setOf(
        0x1549A966L, // Info
        0x1654AE6BL, // Tracks
        0x1F43B675L, // Cluster
        0x1C53BB6BL, // Cues
        0x1941A469L, // Attachments
        0x1043A770L, // Chapters
        0x1254C367L, // Tags
        0x114D9B74L  // SeekHead
    )

    private class Vint(val value: Long, val width: Int, val allOnes: Boolean)

    override fun determineExtent(
        source: RawSource,
        start: Long,
        signature: FileSignature
    ): CarveExtent? {
        val buffer = ByteArray(16)
        if (source.readFully(start, buffer, 0, 16) < 12) return null

        val headerId = readId(source, start, buffer) ?: return null
        if (headerId.value != EBML_HEADER_ID) return null

        var pos = start + headerId.width
        val headerSize = readSize(source, pos, buffer) ?: return null
        pos += headerSize.width + headerSize.value

        val segmentId = readId(source, pos, buffer) ?: return null
        if (segmentId.value != SEGMENT_ID) {
            // ترويسة EBML صالحة لكن بلا Segment — بقايا ملف فقط
            val length = (pos - start).coerceAtLeast(signature.minSize)
            return CarveExtent(length, 20, truncated = true, note = "no-segment")
        }
        pos += segmentId.width

        val segmentSize = readSize(source, pos, buffer) ?: return null
        pos += segmentSize.width

        if (!segmentSize.allOnes && segmentSize.value > 0) {
            val total = pos + segmentSize.value - start
            if (total in signature.minSize..signature.maxSize &&
                (source.length <= 0 || start + total <= source.length)
            ) {
                return CarveExtent(total, 100, truncated = false, note = "segment-size")
            }
        }

        // حجم غير معروف: نمشي على العناصر العليا
        var clusters = 0
        val limit = start + signature.maxSize
        while (pos < limit) {
            val id = readId(source, pos, buffer) ?: break
            if (id.value !in TOP_LEVEL_IDS) break
            val size = readSize(source, pos + id.width, buffer) ?: break
            if (size.allOnes) break
            if (id.value == 0x1F43B675L) clusters++
            val next = pos + id.width + size.width + size.value
            if (next <= pos) break
            if (source.length > 0 && next > source.length) break
            pos = next
        }

        val length = pos - start
        if (length < signature.minSize) return null
        val confidence = when {
            clusters >= 4 -> 85
            clusters >= 1 -> 65
            else -> 35
        }
        return CarveExtent(length, confidence, truncated = true, note = "clusters=$clusters")
    }

    /** يقرأ معرّف عنصر EBML مع الاحتفاظ ببايتات العلامة. */
    private fun readId(source: RawSource, pos: Long, buffer: ByteArray): Vint? {
        if (source.readFully(pos, buffer, 0, 4) < 1) return null
        val first = buffer[0].toInt() and 0xFF
        val width = leadingWidth(first) ?: return null
        if (width > 4) return null
        var value = 0L
        for (i in 0 until width) {
            value = (value shl 8) or (buffer[i].toLong() and 0xFF)
        }
        return Vint(value, width, allOnes = false)
    }

    /** يقرأ حجم عنصر EBML بعد إزالة بت العلامة. */
    private fun readSize(source: RawSource, pos: Long, buffer: ByteArray): Vint? {
        if (source.readFully(pos, buffer, 0, 8) < 1) return null
        val first = buffer[0].toInt() and 0xFF
        val width = leadingWidth(first) ?: return null
        var value = (first and (0xFF shr width)).toLong()
        var allOnes = value == (0xFFL shr width)
        for (i in 1 until width) {
            val b = buffer[i].toInt() and 0xFF
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        return Vint(value, width, allOnes)
    }

    /** عدد بايتات vint من موضع أول بت مضبوط. */
    private fun leadingWidth(first: Int): Int? {
        if (first == 0) return null
        var mask = 0x80
        var width = 1
        while (mask != 0 && (first and mask) == 0) {
            mask = mask shr 1
            width++
        }
        return if (width in 1..8) width else null
    }
}
