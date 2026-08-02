package com.deeprecovery.pro.engine.carver

import com.deeprecovery.pro.data.model.MediaType

/**
 * توقيع ملف (Magic Number) يُستخدم في File Carving.
 *
 * الاعتماد على التوقيع وليس على اسم الملف هو أساس الاستعادة العميقة:
 * عند حذف الملف يفقد اسمه ومدخل الفهرس، لكن البايتات نفسها تبقى
 * على القرص حتى يُكتب فوقها.
 *
 * @param header البايتات التي تبدأ بها بنية الملف.
 * @param headerOffset إزاحة التوقيع داخل الملف (مثلاً ISO-BMFF يبدأ عند 4).
 * @param maskedIndices مواضع داخل [header] يجب تجاهلها عند المطابقة (بايتات متغيّرة).
 */
data class FileSignature(
    val id: String,
    val extension: String,
    val mimeType: String,
    val mediaType: MediaType,
    val header: ByteArray,
    val headerOffset: Int = 0,
    val maskedIndices: Set<Int> = emptySet(),
    val minSize: Long = 1024L,
    val maxSize: Long = 512L * 1024 * 1024,
    val carverId: String
) {
    /** يطابق [header] مع [buffer] بدءاً من [index] (وهو موضع بداية الملف). */
    fun matchesAt(buffer: ByteArray, index: Int, limit: Int): Boolean {
        val start = index + headerOffset
        if (start < 0 || start + header.size > limit) return false
        for (i in header.indices) {
            if (i in maskedIndices) continue
            if (buffer[start + i] != header[i]) return false
        }
        return true
    }

    override fun equals(other: Any?): Boolean = other is FileSignature && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * سجل التواقيع المدعومة.
 *
 * الصور: JPEG / JPG، PNG، WEBP، HEIC
 * الفيديو: MP4، MOV، 3GP، AVI، MKV
 */
object SignatureRegistry {

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    // ---------------------------------------------------------------- الصور

    val JPEG = FileSignature(
        id = "jpeg",
        extension = "jpg",
        mimeType = "image/jpeg",
        mediaType = MediaType.IMAGE,
        // FF D8 FF — البايت الرابع متغيّر (E0/E1/DB/EE...)
        header = bytes(0xFF, 0xD8, 0xFF),
        minSize = 2 * 1024,
        maxSize = 64L * 1024 * 1024,
        carverId = JpegCarver.ID
    )

    val PNG = FileSignature(
        id = "png",
        extension = "png",
        mimeType = "image/png",
        mediaType = MediaType.IMAGE,
        header = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        minSize = 1024,
        maxSize = 64L * 1024 * 1024,
        carverId = PngCarver.ID
    )

    val WEBP = FileSignature(
        id = "webp",
        extension = "webp",
        mimeType = "image/webp",
        mediaType = MediaType.IMAGE,
        // RIFF ???? WEBP — نطابق RIFF ثم WEBP عند الإزاحة 8
        header = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP"),
        maskedIndices = setOf(4, 5, 6, 7),
        minSize = 512,
        maxSize = 64L * 1024 * 1024,
        carverId = RiffCarver.ID
    )

    val HEIC = FileSignature(
        id = "heic",
        extension = "heic",
        mimeType = "image/heic",
        mediaType = MediaType.IMAGE,
        // ???? ftypheic — التوقيع عند الإزاحة 4
        header = ascii("ftypheic"),
        headerOffset = 4,
        minSize = 4 * 1024,
        maxSize = 64L * 1024 * 1024,
        carverId = IsoBmffCarver.ID
    )

    val HEIF = HEIC.copy(
        id = "heif",
        header = ascii("ftypmif1"),
        extension = "heic"
    )

    val HEIX = HEIC.copy(
        id = "heix",
        header = ascii("ftypheix"),
        extension = "heic"
    )

    // -------------------------------------------------------------- الفيديو

    val MP4 = FileSignature(
        id = "mp4",
        extension = "mp4",
        mimeType = "video/mp4",
        mediaType = MediaType.VIDEO,
        header = ascii("ftypisom"),
        headerOffset = 4,
        minSize = 32 * 1024,
        maxSize = 4L * 1024 * 1024 * 1024,
        carverId = IsoBmffCarver.ID
    )

    val MP4_V2 = MP4.copy(id = "mp4_mp42", header = ascii("ftypmp42"))
    val MP4_AVC = MP4.copy(id = "mp4_avc1", header = ascii("ftypavc1"))
    val MP4_DASH = MP4.copy(id = "mp4_dash", header = ascii("ftypdash"))
    val MP4_ISO2 = MP4.copy(id = "mp4_iso2", header = ascii("ftypiso2"))
    val MP4_M4V = MP4.copy(id = "m4v", header = ascii("ftypM4V "), extension = "m4v")

    val MOV = FileSignature(
        id = "mov",
        extension = "mov",
        mimeType = "video/quicktime",
        mediaType = MediaType.VIDEO,
        header = ascii("ftypqt  "),
        headerOffset = 4,
        minSize = 32 * 1024,
        maxSize = 4L * 1024 * 1024 * 1024,
        carverId = IsoBmffCarver.ID
    )

    /** بعض ملفات MOV القديمة تبدأ مباشرة بـ moov أو mdat بدل ftyp. */
    val MOV_MOOV = MOV.copy(id = "mov_moov", header = ascii("moov"), headerOffset = 4)

    val THREE_GP = FileSignature(
        id = "3gp",
        extension = "3gp",
        mimeType = "video/3gpp",
        mediaType = MediaType.VIDEO,
        header = ascii("ftyp3gp"),
        headerOffset = 4,
        minSize = 16 * 1024,
        maxSize = 2L * 1024 * 1024 * 1024,
        carverId = IsoBmffCarver.ID
    )

    val THREE_G2 = THREE_GP.copy(id = "3g2", header = ascii("ftyp3g2"), extension = "3g2")

    val AVI = FileSignature(
        id = "avi",
        extension = "avi",
        mimeType = "video/x-msvideo",
        mediaType = MediaType.VIDEO,
        header = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("AVI "),
        maskedIndices = setOf(4, 5, 6, 7),
        minSize = 32 * 1024,
        maxSize = 4L * 1024 * 1024 * 1024,
        carverId = RiffCarver.ID
    )

    val MKV = FileSignature(
        id = "mkv",
        extension = "mkv",
        mimeType = "video/x-matroska",
        mediaType = MediaType.VIDEO,
        // EBML header: 1A 45 DF A3
        header = bytes(0x1A, 0x45, 0xDF, 0xA3),
        minSize = 32 * 1024,
        maxSize = 4L * 1024 * 1024 * 1024,
        carverId = MatroskaCarver.ID
    )

    val imageSignatures: List<FileSignature> = listOf(JPEG, PNG, WEBP, HEIC, HEIF, HEIX)

    val videoSignatures: List<FileSignature> = listOf(
        MP4, MP4_V2, MP4_AVC, MP4_DASH, MP4_ISO2, MP4_M4V,
        MOV, MOV_MOOV, THREE_GP, THREE_G2, AVI, MKV
    )

    val all: List<FileSignature> = imageSignatures + videoSignatures

    /** أطول توقيع (بالبايت) — يحدد حجم التداخل بين قطع القراءة. */
    val maxSignatureSpan: Int = all.maxOf { it.headerOffset + it.header.size }

    fun signaturesFor(images: Boolean, videos: Boolean): List<FileSignature> = buildList {
        if (images) addAll(imageSignatures)
        if (videos) addAll(videoSignatures)
    }

    fun byId(id: String): FileSignature? = all.firstOrNull { it.id == id }
}
