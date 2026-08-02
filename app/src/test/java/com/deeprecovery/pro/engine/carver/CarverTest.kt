package com.deeprecovery.pro.engine.carver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * اختبارات محركات النحت على بيانات مُركّبة.
 *
 * تتحقق من أن كل محرك يحدد نهاية الملف بدقة وسط بيانات عشوائية،
 * وهو جوهر File Carving.
 */
class CarverTest {

    // ------------------------------------------------------------- أدوات

    private fun noise(size: Int, seed: Int = 7): ByteArray {
        val data = ByteArray(size)
        var state = seed
        for (i in data.indices) {
            state = state * 1103515245 + 12345
            data[i] = ((state shr 16) and 0x7F).toByte()
        }
        return data
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte()
    )

    private fun ByteArrayOutputStream.w(vararg values: Int) {
        values.forEach { write(it) }
    }

    // -------------------------------------------------------------- JPEG

    /** يبني JPEG صالحاً: SOI + APP0 + SOF0 + SOS + بيانات + EOI. */
    private fun buildJpeg(payload: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        out.w(0xFF, 0xD8)                       // SOI
        out.w(0xFF, 0xE0, 0x00, 0x10)           // APP0 بطول 16
        out.write(ByteArray(14))
        out.w(0xFF, 0xC0, 0x00, 0x0B)           // SOF0 بطول 11
        out.write(ByteArray(9))
        out.w(0xFF, 0xDA, 0x00, 0x08)           // SOS بطول 8
        out.write(ByteArray(6))
        // بيانات مضغوطة تحتوي FF00 كحشو، ويجب ألا تُعد نهاية
        val entropy = ByteArray(payload) { if (it % 512 == 0) 0xFF.toByte() else 0x42 }
        for (i in entropy.indices) {
            out.write(entropy[i].toInt() and 0xFF)
            if ((entropy[i].toInt() and 0xFF) == 0xFF) out.write(0x00)
        }
        out.w(0xFF, 0xD9)                       // EOI
        return out.toByteArray()
    }

    @Test
    fun `يستخرج JPEG كاملاً وسط بيانات عشوائية`() {
        val jpeg = buildJpeg()
        val prefix = noise(3000)
        val data = prefix + jpeg + noise(5000, seed = 11)

        val source = ByteArrayRawSource(data)
        val extent = JpegCarver.determineExtent(
            source,
            prefix.size.toLong(),
            SignatureRegistry.JPEG
        )

        assertNotNull(extent)
        assertEquals(jpeg.size.toLong(), extent!!.length)
        assertFalse(extent.truncated)
        assertEquals("EOI", extent.note)
        assertTrue(extent.structureConfidence >= 80)
    }

    @Test
    fun `يعلّم JPEG المقطوع كملف ناقص ويحتفظ بالجزء المتاح`() {
        val jpeg = buildJpeg(8192)
        val truncated = jpeg.copyOfRange(0, jpeg.size - 900)

        val extent = JpegCarver.determineExtent(
            ByteArrayRawSource(truncated),
            0L,
            SignatureRegistry.JPEG
        )

        assertNotNull(extent)
        assertTrue(extent!!.truncated)
        assertEquals("no-EOI", extent.note)
        // نحتفظ بكل البيانات المتاحة بدل التوقف عند علامة SOS
        assertEquals(truncated.size.toLong(), extent.length)
        assertTrue(extent.structureConfidence < 60)
    }

    @Test
    fun `يرفض بيانات لا تبدأ بتوقيع JPEG`() {
        val extent = JpegCarver.determineExtent(
            ByteArrayRawSource(noise(8192)),
            0L,
            SignatureRegistry.JPEG
        )
        assertNull(extent)
    }

    // --------------------------------------------------------------- PNG

    private fun buildPng(idatSize: Int = 2048): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun chunk(type: String, body: ByteArray) {
            out.write(be32(body.size))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.write(be32(0)) // CRC وهمي — المحرك لا يتحقق منه
        }

        chunk("IHDR", ByteArray(13))
        chunk("IDAT", ByteArray(idatSize))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `يستخرج PNG حتى IEND`() {
        val png = buildPng()
        val prefix = noise(1024)
        val data = prefix + png + noise(2048, seed = 3)

        val extent = PngCarver.determineExtent(
            ByteArrayRawSource(data),
            prefix.size.toLong(),
            SignatureRegistry.PNG
        )

        assertNotNull(extent)
        assertEquals(png.size.toLong(), extent!!.length)
        assertFalse(extent.truncated)
        assertEquals(100, extent.structureConfidence)
    }

    // -------------------------------------------------------------- RIFF

    private fun buildRiff(fourCc: String, payload: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        body.write(fourCc.toByteArray(Charsets.US_ASCII))
        // جزء داخلي واحد صالح
        body.write("data".toByteArray(Charsets.US_ASCII))
        body.write(le32(payload))
        body.write(ByteArray(payload))

        val bodyBytes = body.toByteArray()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(bodyBytes.size))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    @Test
    fun `يحسب حجم WEBP من ترويسة RIFF`() {
        val webp = buildRiff("WEBP", 4096)
        val data = webp + noise(1024)

        val extent = RiffCarver.determineExtent(
            ByteArrayRawSource(data),
            0L,
            SignatureRegistry.WEBP
        )

        assertNotNull(extent)
        assertEquals(webp.size.toLong(), extent!!.length)
        assertFalse(extent.truncated)
    }

    @Test
    fun `يحسب حجم AVI من ترويسة RIFF`() {
        val avi = buildRiff("AVI ", 64 * 1024)
        val extent = RiffCarver.determineExtent(
            ByteArrayRawSource(avi),
            0L,
            SignatureRegistry.AVI
        )

        assertNotNull(extent)
        assertEquals(avi.size.toLong(), extent!!.length)
    }

    // ---------------------------------------------------------- ISO-BMFF

    private fun box(type: String, payloadSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(payloadSize + 8))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(payloadSize))
        return out.toByteArray()
    }

    private fun buildMp4(brand: String = "isom", mdatSize: Int = 64 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val ftypBody = ByteArrayOutputStream()
        ftypBody.write(brand.toByteArray(Charsets.US_ASCII))
        ftypBody.write(be32(512))
        ftypBody.write(brand.toByteArray(Charsets.US_ASCII))

        val ftypBytes = ftypBody.toByteArray()
        out.write(be32(ftypBytes.size + 8))
        out.write("ftyp".toByteArray(Charsets.US_ASCII))
        out.write(ftypBytes)

        out.write(box("moov", 2048))
        out.write(box("mdat", mdatSize))
        return out.toByteArray()
    }

    @Test
    fun `يمشي على صناديق MP4 ويحدد النهاية`() {
        val mp4 = buildMp4()
        val data = mp4 + noise(4096, seed = 5)

        val extent = IsoBmffCarver.determineExtent(
            ByteArrayRawSource(data),
            0L,
            SignatureRegistry.MP4
        )

        assertNotNull(extent)
        assertEquals(mp4.size.toLong(), extent!!.length)
        assertFalse(extent.truncated)
        assertEquals(100, extent.structureConfidence)
    }

    @Test
    fun `يتعرف على 3GP بنفس محرك ISO-BMFF`() {
        val file = buildMp4(brand = "3gp4", mdatSize = 32 * 1024)
        val extent = IsoBmffCarver.determineExtent(
            ByteArrayRawSource(file),
            0L,
            SignatureRegistry.THREE_GP
        )

        assertNotNull(extent)
        assertEquals(file.size.toLong(), extent!!.length)
    }

    @Test
    fun `يخفض الثقة عندما ينقص صندوق moov`() {
        val out = ByteArrayOutputStream()
        val ftypBody = "isom".toByteArray(Charsets.US_ASCII) + be32(512) +
            "isom".toByteArray(Charsets.US_ASCII)
        out.write(be32(ftypBody.size + 8))
        out.write("ftyp".toByteArray(Charsets.US_ASCII))
        out.write(ftypBody)
        out.write(box("mdat", 64 * 1024))

        val extent = IsoBmffCarver.determineExtent(
            ByteArrayRawSource(out.toByteArray()),
            0L,
            SignatureRegistry.MP4
        )

        assertNotNull(extent)
        assertTrue(extent!!.truncated)
        assertTrue(extent.structureConfidence < 100)
    }

    // ---------------------------------------------------------- Matroska

    /** يكتب vint بعرض 8 بايت (أبسط شكل يقبله المحرك). */
    private fun vint8(value: Long): ByteArray {
        val out = ByteArray(8)
        out[0] = 0x01
        for (i in 1 until 8) {
            out[i] = ((value shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun buildMkv(segmentPayload: Int = 32 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        // EBML header
        out.write(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        out.write(vint8(16))
        out.write(ByteArray(16))
        // Segment
        out.write(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        out.write(vint8(segmentPayload.toLong()))
        out.write(ByteArray(segmentPayload))
        return out.toByteArray()
    }

    @Test
    fun `يحسب حجم MKV من حجم Segment المعلن`() {
        val mkv = buildMkv()
        val data = mkv + noise(2048, seed = 9)

        val extent = MatroskaCarver.determineExtent(
            ByteArrayRawSource(data),
            0L,
            SignatureRegistry.MKV
        )

        assertNotNull(extent)
        assertEquals(mkv.size.toLong(), extent!!.length)
        assertFalse(extent.truncated)
        assertEquals(100, extent.structureConfidence)
    }

    // ------------------------------------------------------- مطابقة التواقيع

    @Test
    fun `يطابق توقيع HEIC عند الإزاحة الصحيحة`() {
        val buffer = ByteArray(32)
        val ftyp = "ftypheic".toByteArray(Charsets.US_ASCII)
        System.arraycopy(ftyp, 0, buffer, 4, ftyp.size)

        assertTrue(SignatureRegistry.HEIC.matchesAt(buffer, 0, buffer.size))
        assertFalse(SignatureRegistry.MP4.matchesAt(buffer, 0, buffer.size))
    }

    @Test
    fun `يتجاهل البايتات المقنّعة في توقيع RIFF`() {
        val buffer = "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x11, 0x22, 0x33, 0x44) +
            "WEBP".toByteArray(Charsets.US_ASCII)

        assertTrue(SignatureRegistry.WEBP.matchesAt(buffer, 0, buffer.size))
        assertFalse(SignatureRegistry.AVI.matchesAt(buffer, 0, buffer.size))
    }

    @Test
    fun `سجل التواقيع يغطي كل الصيغ المطلوبة`() {
        val extensions = SignatureRegistry.all.map { it.extension }.toSet()
        listOf("jpg", "png", "webp", "heic", "mp4", "mov", "3gp", "avi", "mkv").forEach {
            assertTrue("التوقيع مفقود: $it", it in extensions)
        }
    }
}
