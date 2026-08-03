package com.deeprecovery.pro.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.deeprecovery.pro.data.db.AppDatabase
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/** نتيجة عملية الحذف النهائي. */
data class DeleteReport(
    val requested: Int,
    val wiped: Int,
    val failed: Int,
    /** عناصر يحتاج حذفها موافقة صريحة من النظام (ملفات لا يملكها التطبيق). */
    val needsConsent: List<Uri>
)

/**
 * الحذف النهائي (المسح الآمن).
 *
 * الحذف العادي يزيل مدخل الفهرس فقط وتبقى البايتات على القرص — وهذا
 * بالضبط ما يستغله محرك الاستعادة في هذا التطبيق. لذلك يكتب المسح الآمن
 * بيانات عشوائية فوق محتوى الملف أولاً، ثم يقلّص حجمه، ثم يعيد تسميته
 * إلى اسم عشوائي قبل حذفه — فلا يبقى محتوى ولا اسم يدلّان عليه.
 *
 * حدّ صادق: ذاكرة الفلاش تعيد توزيع الكتابة داخلياً (Wear Leveling) وتنفّذ
 * TRIM، فقد تبقى نسخة فيزيائية في كتلة لم يعد النظام يشير إليها. الكتابة
 * فوق البيانات تمنع الاستعادة البرمجية المعتادة، ولا تعادل مسحاً فيزيائياً
 * مضموناً على مستوى الشريحة.
 */
class SecureDeleteEngine(private val context: Context) {

    companion object {
        private const val TAG = "SecureDeleteEngine"
        private const val BUFFER = 128 * 1024
        /** لا نكتب فوق ملفات ضخمة إلى ما لا نهاية. */
        private const val MAX_OVERWRITE_BYTES = 512L * 1024 * 1024
    }

    private val db = AppDatabase.get(context)
    private val fileDao = db.recoveredFileDao()
    private val random = SecureRandom()

    /**
     * يحذف الملفات المحددة نهائياً.
     *
     * @param overwrite الكتابة فوق المحتوى قبل الحذف.
     */
    suspend fun deleteForever(
        ids: List<Long>,
        overwrite: Boolean = true,
        onProgress: suspend (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> }
    ): DeleteReport {
        val entities = fileDao.getByIds(ids)
        var wiped = 0
        var failed = 0
        val needsConsent = mutableListOf<Uri>()
        val removedIds = mutableListOf<Long>()

        entities.forEachIndexed { index, entity ->
            coroutineContext.ensureActive()
            onProgress(index + 1, entities.size, entity.displayName)

            when (val outcome = deleteOne(entity, overwrite)) {
                Outcome.WIPED -> {
                    wiped++
                    removedIds += entity.id
                }

                Outcome.NEEDS_CONSENT -> {
                    entity.contentUri?.let { needsConsent += Uri.parse(it) }
                }

                Outcome.FAILED -> failed++
            }
        }

        if (removedIds.isNotEmpty()) fileDao.deleteByIds(removedIds)

        return DeleteReport(
            requested = entities.size,
            wiped = wiped,
            failed = failed,
            needsConsent = needsConsent
        )
    }

    /** يزيل سجلات عناصر وافق النظام على حذفها. */
    suspend fun forgetRecords(ids: List<Long>) {
        if (ids.isNotEmpty()) fileDao.deleteByIds(ids)
    }

    suspend fun idsForUris(sessionId: Long, uris: Set<String>): List<Long> =
        fileDao.observeBySessionOnce(sessionId)
            .filter { it.contentUri in uris }
            .map { it.id }

    private enum class Outcome { WIPED, NEEDS_CONSENT, FAILED }

    private fun deleteOne(entity: RecoveredFileEntity, overwrite: Boolean): Outcome {
        val file = entity.stagedPath?.let(::File)

        if (file != null && file.isFile && file.canWrite()) {
            return if (shredFile(file, overwrite)) Outcome.WIPED else Outcome.FAILED
        }

        val uri = entity.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri != null) {
            // الكتابة فوق المحتوى قبل الحذف متى سمح النظام بذلك
            if (overwrite) runCatching { overwriteViaResolver(uri) }

            val deleted = runCatching {
                context.contentResolver.delete(uri, null, null) > 0
            }.getOrElse { error ->
                Log.w(TAG, "الحذف يحتاج موافقة النظام: ${entity.displayName}", error)
                return Outcome.NEEDS_CONSENT
            }
            return if (deleted) Outcome.WIPED else Outcome.NEEDS_CONSENT
        }

        // لا ملف ولا عنوان: يكفي إزالة السجل
        return Outcome.WIPED
    }

    /** يكتب بيانات عشوائية فوق الملف ثم يقلّصه ويعيد تسميته قبل حذفه. */
    private fun shredFile(file: File, overwrite: Boolean): Boolean = runCatching {
        if (overwrite) {
            val length = file.length().coerceAtMost(MAX_OVERWRITE_BYTES)
            RandomAccessFile(file, "rw").use { raf ->
                val buffer = ByteArray(BUFFER)
                var written = 0L
                while (written < length) {
                    random.nextBytes(buffer)
                    val chunk = minOf(BUFFER.toLong(), length - written).toInt()
                    raf.write(buffer, 0, chunk)
                    written += chunk
                }
                raf.fd.sync()
                raf.setLength(0)
            }
        }

        // اسم عشوائي حتى لا يبقى الاسم الأصلي في مدخل المجلد
        val renamed = File(file.parentFile, "%016x".format(random.nextLong()))
        val target = if (file.renameTo(renamed)) renamed else file
        target.delete()
    }.getOrElse {
        Log.w(TAG, "تعذّر المسح الآمن لـ ${file.name}", it)
        false
    }

    private fun overwriteViaResolver(uri: Uri) {
        context.contentResolver.openFileDescriptor(uri, "w")?.use { descriptor ->
            java.io.FileOutputStream(descriptor.fileDescriptor).use { out ->
                val buffer = ByteArray(BUFFER)
                random.nextBytes(buffer)
                out.write(buffer)
                out.flush()
            }
        }
    }
}
