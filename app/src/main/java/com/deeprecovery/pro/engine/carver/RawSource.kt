package com.deeprecovery.pro.engine.carver

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * مصدر بيانات خام يمكن قراءته بالوصول العشوائي.
 *
 * ينفّذه:
 *  - [FileRawSource] لملف عادي أو صورة قسم.
 *  - `BlockDeviceRawSource` لقراءة `/dev/block/...` عبر Root.
 */
interface RawSource : Closeable {

    /** اسم يُعرض للمستخدم (مسار الملف أو اسم الجهاز الكتلي). */
    val displayName: String

    /** الحجم الكلي بالبايت، أو -1 إذا كان غير معروف. */
    val length: Long

    /**
     * يقرأ حتى [length] بايت بدءاً من [position] المطلقة.
     * @return عدد البايتات المقروءة فعلياً، أو -1 عند نهاية المصدر.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** يقرأ بالضبط [length] بايت أو أقل عند النهاية. */
    fun readFully(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = readAt(position + total, buffer, offset + total, length - total)
            if (read <= 0) break
            total += read
        }
        return total
    }
}

/** مصدر خام مبني على ملف عادي عبر [RandomAccessFile]. */
class FileRawSource(private val file: File) : RawSource {

    private val raf = RandomAccessFile(file, "r")

    override val displayName: String = file.absolutePath
    override val length: Long = runCatching { raf.length() }.getOrDefault(0L)

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        raf.seek(position)
        return raf.read(buffer, offset, length)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}

/** مصدر خام يقرأ من مصفوفة بايتات في الذاكرة (يُستخدم في الاختبارات). */
class ByteArrayRawSource(
    private val data: ByteArray,
    override val displayName: String = "memory"
) : RawSource {

    override val length: Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(length, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun close() = Unit
}
