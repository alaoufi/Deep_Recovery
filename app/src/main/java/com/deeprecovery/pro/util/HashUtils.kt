package com.deeprecovery.pro.util

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * بصمة محتوى تُستخدم في **كشف الملفات المكررة**.
 *
 * لتفادي قراءة ملفات فيديو بحجم غيغابايت كاملة، نأخذ بصمة من:
 * الحجم + أول 64KB + آخر 64KB + عيّنة من المنتصف. هذا كافٍ عملياً
 * للتمييز بين الملفات مع بقاء العملية سريعة.
 */
object HashUtils {

    private const val SAMPLE = 64 * 1024

    fun contentHash(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val length = file.length()
        digest.update(length.toString().toByteArray())

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(SAMPLE)

            fun digestAt(position: Long) {
                if (position < 0 || position >= length) return
                raf.seek(position)
                val read = raf.read(buffer, 0, SAMPLE)
                if (read > 0) digest.update(buffer, 0, read)
            }

            digestAt(0)
            if (length > SAMPLE * 2) digestAt(length / 2)
            if (length > SAMPLE) digestAt((length - SAMPLE).coerceAtLeast(0))
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrElse { "" }
}
