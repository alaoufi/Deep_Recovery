package com.deeprecovery.pro.engine.carver

/**
 * محرك استخراج حاويات RIFF: يغطي **WEBP** و **AVI**.
 *
 * البنية: `RIFF` + `size (little-endian)` + `FourCC` + قائمة أجزاء.
 * الحجم الكلي = 8 + size. نتحقق من صحة الأجزاء الداخلية لرفع الثقة.
 */
object RiffCarver : Carver {

    const val ID = "carver_riff"
    override val id: String = ID

    override fun determineExtent(
        source: RawSource,
        start: Long,
        signature: FileSignature
    ): CarveExtent? {
        val head = ByteArray(12)
        if (source.readFully(start, head, 0, 12) < 12) return null
        if (ByteReader.ascii(head, 0, 4) != "RIFF") return null

        val declared = ByteReader.u32le(head, 4)
        val fourCc = ByteReader.ascii(head, 8, 4)
        val total = declared + 8

        val plausible = declared > 0 &&
            total >= signature.minSize &&
            total <= signature.maxSize &&
            (source.length <= 0 || start + total <= source.length)

        val chunksValid = validateChunks(source, start + 12, start + total, signature)

        if (plausible) {
            val confidence = when {
                chunksValid >= 3 -> 100
                chunksValid >= 1 -> 80
                else -> 55
            }
            return CarveExtent(
                length = total,
                structureConfidence = confidence,
                truncated = false,
                note = "RIFF/$fourCc"
            )
        }

        // الحجم المعلن غير منطقي — نستعيد ما نستطيع الوصول إليه
        val available = if (source.length > 0) {
            (source.length - start).coerceAtMost(signature.maxSize)
        } else {
            signature.minSize
        }
        if (available < signature.minSize) return null
        return CarveExtent(
            length = available,
            structureConfidence = 25,
            truncated = true,
            note = "RIFF/$fourCc bad-size"
        )
    }

    /** يعدّ الأجزاء الداخلية السليمة (بحد أقصى بسيط لتفادي البطء). */
    private fun validateChunks(
        source: RawSource,
        from: Long,
        end: Long,
        signature: FileSignature
    ): Int {
        var pos = from
        var valid = 0
        val head = ByteArray(8)
        while (pos + 8 <= end && valid < 8) {
            if (source.readFully(pos, head, 0, 8) < 8) break
            val type = ByteReader.ascii(head, 0, 4)
            if (!type.all { it.code in 0x20..0x7E }) break
            var size = ByteReader.u32le(head, 4)
            if (size < 0 || size > signature.maxSize) break
            if (size % 2 == 1L) size += 1 // حشو المحاذاة
            valid++
            pos += 8 + size
        }
        return valid
    }
}
