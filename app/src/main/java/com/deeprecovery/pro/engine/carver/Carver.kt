package com.deeprecovery.pro.engine.carver

/**
 * نتيجة تحديد امتداد ملف محذوف داخل البيانات الخام.
 *
 * @param length الطول المُقدَّر للملف بالبايت.
 * @param structureConfidence ثقة بنيوية 0..100 مبنية على اكتمال بنية الملف.
 * @param truncated هل انتهى الملف بشكل غير مكتمل (مقطوع).
 * @param note سبب مختصر يُخزَّن للعرض في التقرير.
 */
data class CarveExtent(
    val length: Long,
    val structureConfidence: Int,
    val truncated: Boolean,
    val note: String
)

/**
 * محرك تحليل بنية نوع ملفات معيّن.
 *
 * لا يعتمد على اسم الملف إطلاقاً — فقط على البنية الداخلية للبايتات،
 * وهذا هو جوهر File Carving.
 */
interface Carver {

    val id: String

    /**
     * يحدد نهاية الملف الذي يبدأ عند [start] داخل [source].
     *
     * @return [CarveExtent] أو null إذا لم تكن البنية صالحة أصلاً.
     */
    fun determineExtent(source: RawSource, start: Long, signature: FileSignature): CarveExtent?
}

/** أدوات قراءة أعداد من مصفوفة بايتات. */
internal object ByteReader {

    fun u32be(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or
            ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or
            (b[i + 3].toLong() and 0xFF)

    fun u64be(b: ByteArray, i: Int): Long {
        var value = 0L
        for (k in 0 until 8) {
            value = (value shl 8) or (b[i + k].toLong() and 0xFF)
        }
        return value
    }

    fun u32le(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24)

    fun ascii(b: ByteArray, i: Int, len: Int): String =
        String(b, i, len, Charsets.US_ASCII)
}
