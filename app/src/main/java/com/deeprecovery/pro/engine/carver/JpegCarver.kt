package com.deeprecovery.pro.engine.carver

/**
 * محرك استخراج JPEG/JPG.
 *
 * يمشي على علامات (Markers) الملف بدءاً من SOI (FFD8) ويحلّل كل مقطع:
 *  - المقاطع ذات الطول تُتخطّى بطولها المعلن.
 *  - عند SOS (FFDA) تبدأ البيانات المضغوطة، فنبحث عن أول علامة حقيقية
 *    مع تجاهل FF00 (byte stuffing) وعلامات إعادة التزامن RSTn.
 *  - النهاية الصحيحة هي EOI (FFD9).
 */
object JpegCarver : Carver {

    const val ID = "carver_jpeg"
    override val id: String = ID

    private const val CHUNK = 64 * 1024

    override fun determineExtent(
        source: RawSource,
        start: Long,
        signature: FileSignature
    ): CarveExtent? {
        val header = ByteArray(4)
        if (source.readFully(start, header, 0, 4) < 4) return null
        if ((header[0].toInt() and 0xFF) != 0xFF || (header[1].toInt() and 0xFF) != 0xD8) return null

        val hardLimit = signature.maxSize
        var pos = start + 2
        var sawSof = false
        var sawSos = false
        val marker = ByteArray(4)

        while (pos - start < hardLimit) {
            if (source.readFully(pos, marker, 0, 2) < 2) break
            if ((marker[0].toInt() and 0xFF) != 0xFF) {
                // فقدنا التزامن — نحاول إعادة الالتقاط بالبحث عن EOI
                break
            }
            val code = marker[1].toInt() and 0xFF
            when {
                code == 0xD9 -> {
                    // EOI — نهاية سليمة
                    val length = pos + 2 - start
                    if (length < signature.minSize) return null
                    val confidence = when {
                        sawSof && sawSos -> 100
                        sawSos -> 85
                        else -> 60
                    }
                    return CarveExtent(length, confidence, truncated = false, note = "EOI")
                }

                code == 0x01 || code in 0xD0..0xD8 -> {
                    // علامات بلا محتوى
                    pos += 2
                }

                code == 0xDA -> {
                    sawSos = true
                    if (source.readFully(pos + 2, marker, 0, 2) < 2) break
                    val segLen = ((marker[0].toInt() and 0xFF) shl 8) or (marker[1].toInt() and 0xFF)
                    if (segLen < 2) break
                    val scanStart = pos + 2 + segLen
                    val end = scanEntropyData(source, scanStart, start + hardLimit)
                    if (end == null) {
                        // انتهت البيانات المضغوطة بلا علامة نهاية: الملف مقطوع.
                        // نحتفظ بكل ما توفّر — الجزء العلوي من الصورة يبقى قابلاً للعرض.
                        pos = if (source.length > 0) {
                            minOf(source.length, start + hardLimit)
                        } else {
                            start + hardLimit
                        }
                        break
                    }
                    pos = end
                }

                else -> {
                    if (code in 0xC0..0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC) {
                        sawSof = true
                    }
                    if (source.readFully(pos + 2, marker, 0, 2) < 2) break
                    val segLen = ((marker[0].toInt() and 0xFF) shl 8) or (marker[1].toInt() and 0xFF)
                    if (segLen < 2) break
                    pos += 2 + segLen
                }
            }
        }

        // لم نجد EOI: الملف مقطوع، لكن قد يظل جزء منه قابلاً للعرض
        val truncatedLength = (pos - start).coerceAtMost(hardLimit)
        if (truncatedLength < signature.minSize) return null
        val confidence = if (sawSos) 45 else 20
        return CarveExtent(
            length = truncatedLength,
            structureConfidence = confidence,
            truncated = true,
            note = "no-EOI"
        )
    }

    /**
     * يتخطّى البيانات المضغوطة بعد SOS ويعيد موضع أول علامة حقيقية.
     * يتجاهل FF00 و FFFF وعلامات RSTn (FFD0..FFD7).
     */
    private fun scanEntropyData(source: RawSource, from: Long, limit: Long): Long? {
        val buffer = ByteArray(CHUNK)
        var pos = from
        var pendingFf = false

        while (pos < limit) {
            val toRead = minOf(CHUNK.toLong(), limit - pos).toInt()
            val read = source.readFully(pos, buffer, 0, toRead)
            if (read <= 0) return null

            var i = 0
            while (i < read) {
                val value = buffer[i].toInt() and 0xFF
                if (pendingFf) {
                    pendingFf = false
                    when {
                        value == 0x00 || value == 0xFF -> Unit // حشو، نكمل
                        value in 0xD0..0xD7 -> Unit             // RSTn، نكمل
                        else -> return pos + i - 1              // علامة حقيقية
                    }
                } else if (value == 0xFF) {
                    pendingFf = true
                }
                i++
            }
            pos += read
            if (read < toRead) return null
        }
        return null
    }
}
