package com.deeprecovery.pro.engine.carver

/**
 * محرك استخراج حاويات ISO-BMFF: يغطي **MP4**، **MOV**، **3GP** و **HEIC**.
 *
 * البنية سلسلة صناديق (Boxes):
 * `[size:4][type:4]` ثم المحتوى، مع حالتين خاصتين:
 *  - `size == 1` → الحجم الحقيقي 64-bit بعد النوع مباشرة.
 *  - `size == 0` → الصندوق يمتد حتى نهاية الملف.
 *
 * الملف يُعد ممتازاً عندما نعثر على `ftyp` + `moov` + `mdat` كاملة.
 */
object IsoBmffCarver : Carver {

    const val ID = "carver_isobmff"
    override val id: String = ID

    private val KNOWN_TYPES = setOf(
        "ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid",
        "meta", "mfra", "moof", "styp", "sidx", "ssix", "prft", "pdin", "iloc", "junk"
    )

    override fun determineExtent(
        source: RawSource,
        start: Long,
        signature: FileSignature
    ): CarveExtent? {
        var pos = start
        val head = ByteArray(16)
        var sawFtyp = false
        var sawMoov = false
        var sawMdat = false
        var complete = false
        val limit = start + signature.maxSize
        var boxCount = 0

        while (pos < limit) {
            val read = source.readFully(pos, head, 0, 16)
            if (read < 8) break

            var size = ByteReader.u32be(head, 0)
            val type = ByteReader.ascii(head, 4, 4)
            if (!type.all { it.code in 0x20..0x7E }) break

            var headerSize = 8L
            if (size == 1L) {
                if (read < 16) break
                size = ByteReader.u64be(head, 8)
                headerSize = 16L
            } else if (size == 0L) {
                // يمتد حتى نهاية المصدر
                val remaining = if (source.length > 0) source.length - pos else 0L
                if (remaining <= 0) break
                size = remaining
                complete = true
            }

            if (size < headerSize || size > signature.maxSize) break
            if (boxCount == 0 && type != "ftyp" && type != "moov" && type != "mdat") break

            when (type) {
                "ftyp" -> sawFtyp = true
                "moov" -> sawMoov = true
                "mdat" -> sawMdat = true
            }
            boxCount++

            // نهاية الملف: أول صندوق بنوع غير معروف يعني أننا خرجنا من الحاوية
            val next = pos + size
            if (source.length > 0 && next > source.length) {
                // الصندوق يتجاوز حدود المصدر — مقطوع
                pos = source.length
                break
            }
            pos = next
            if (complete) break

            // نلقي نظرة على النوع التالي لنعرف هل انتهى الملف
            if (source.readFully(pos, head, 0, 8) < 8) break
            val nextType = ByteReader.ascii(head, 4, 4)
            if (nextType !in KNOWN_TYPES) break
        }

        val length = pos - start
        if (length < signature.minSize) return null

        val truncated = !(sawMoov && sawMdat)
        val confidence = when {
            sawFtyp && sawMoov && sawMdat -> 100
            sawMoov && sawMdat -> 90
            sawFtyp && sawMdat -> 65
            sawFtyp && sawMoov -> 60
            sawMdat -> 45
            else -> 25
        }

        val note = buildString {
            if (sawFtyp) append("ftyp ")
            if (sawMoov) append("moov ")
            if (sawMdat) append("mdat")
        }.trim().ifEmpty { "boxes=$boxCount" }

        return CarveExtent(length, confidence, truncated, note)
    }
}
