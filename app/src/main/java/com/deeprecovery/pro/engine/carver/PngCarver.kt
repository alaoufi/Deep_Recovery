package com.deeprecovery.pro.engine.carver

/**
 * محرك استخراج PNG.
 *
 * بنية PNG سلسلة من الأجزاء (Chunks):
 * `[length:4][type:4][data:length][crc:4]`
 * وتنتهي دائماً بالجزء `IEND`.
 */
object PngCarver : Carver {

    const val ID = "carver_png"
    override val id: String = ID

    private const val SIGNATURE_SIZE = 8
    private const val MAX_CHUNK_SIZE = 64L * 1024 * 1024

    override fun determineExtent(
        source: RawSource,
        start: Long,
        signature: FileSignature
    ): CarveExtent? {
        var pos = start + SIGNATURE_SIZE
        val head = ByteArray(8)
        var sawIhdr = false
        var sawIdat = false
        val limit = start + signature.maxSize

        while (pos < limit) {
            if (source.readFully(pos, head, 0, 8) < 8) break
            val dataLength = ByteReader.u32be(head, 0)
            if (dataLength < 0 || dataLength > MAX_CHUNK_SIZE) break

            val type = ByteReader.ascii(head, 4, 4)
            if (!type.all { it.isLetter() }) break

            when (type) {
                "IHDR" -> sawIhdr = true
                "IDAT" -> sawIdat = true
            }

            val next = pos + 8 + dataLength + 4
            if (type == "IEND") {
                val length = next - start
                if (length < signature.minSize) return null
                val confidence = if (sawIhdr && sawIdat) 100 else 70
                return CarveExtent(length, confidence, truncated = false, note = "IEND")
            }
            pos = next
        }

        val truncatedLength = (pos - start).coerceAtMost(signature.maxSize)
        if (truncatedLength < signature.minSize || !sawIdat) return null
        return CarveExtent(
            length = truncatedLength,
            structureConfidence = if (sawIhdr) 40 else 20,
            truncated = true,
            note = "no-IEND"
        )
    }
}
