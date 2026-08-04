package com.deeprecovery.pro.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.deeprecovery.pro.data.db.AppDatabase
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.db.RecoveryReportEntity
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/** تقرير نتيجة عملية الاستعادة. */
data class RecoveryReport(
    val requested: Int,
    val succeeded: Int,
    val failed: Int,
    val skippedDuplicates: Int,
    val foldersCreated: Int,
    val bytesWritten: Long,
    val destination: String,
    val failures: List<String>,
    /**
     * عناصر في سلة مهملات النظام.
     *
     * لا يمكن فتح بايتاتها لنسخها — يملكها تطبيق آخر — واستعادتها تتم
     * بأمر إلغاء الحذف الذي ينفّذه النظام بموافقة المستخدم، فتعود إلى
     * مكانها الأصلي وتظهر في المعرض فوراً.
     */
    val needsUntrash: List<String> = emptyList()
) {
    val successRate: Int
        get() = if (requested == 0) 0 else (succeeded * 100 / requested)
}

/** تقدّم الاستعادة أثناء التنفيذ. */
data class RecoveryProgress(
    val current: Int,
    val total: Int,
    val currentName: String,
    val currentFolder: String,
    val bytesWritten: Long
)

/**
 * محرك الاستعادة.
 *
 * يكتب الملفات إلى الوجهة التي اختارها المستخدم — إما مجلد عبر
 * **Storage Access Framework** أو مجلد عام مباشر.
 *
 * الأهم: **يُرجع المجلدات كاملة بما فيها**. عند استعادة مجلد يُعاد بناء
 * نفس شجرة المجلدات الأصلية في الوجهة (`DCIM/Camera`, `WhatsApp/Media/...`)
 * وتوضع الملفات داخلها بدل تفريغ كل شيء في مجلد واحد.
 */
class RecoveryEngine(private val context: Context) {

    companion object {
        private const val TAG = "RecoveryEngine"
        private const val BUFFER = 256 * 1024
        const val ROOT_FOLDER_NAME = "DeepRecoveryPro"
    }

    private val db = AppDatabase.get(context)
    private val fileDao = db.recoveredFileDao()
    private val reportDao = db.recoveryReportDao()

    /**
     * يستعيد **مجلداً كاملاً بما فيه** — بما في ذلك المجلدات الفرعية.
     *
     * @param folderPath مسار المجلد كما هو مخزّن في النتائج.
     */
    suspend fun recoverFolder(
        sessionId: Long,
        folderPath: String,
        destination: Uri?,
        preserveStructure: Boolean,
        skipDuplicates: Boolean,
        albumName: String = ROOT_FOLDER_NAME,
        onProgress: suspend (RecoveryProgress) -> Unit = {}
    ): RecoveryReport {
        val files = fileDao.getFolderTreeContents(sessionId, folderPath)
        return recover(
            sessionId, files, destination, preserveStructure, skipDuplicates, albumName, onProgress
        )
    }

    /** يستعيد مجموعة ملفات محددة بالمعرّفات. */
    suspend fun recoverFiles(
        sessionId: Long,
        fileIds: List<Long>,
        destination: Uri?,
        preserveStructure: Boolean,
        skipDuplicates: Boolean,
        albumName: String = ROOT_FOLDER_NAME,
        onProgress: suspend (RecoveryProgress) -> Unit = {}
    ): RecoveryReport {
        val files = fileDao.getByIds(fileIds)
        return recover(
            sessionId, files, destination, preserveStructure, skipDuplicates, albumName, onProgress
        )
    }

    private suspend fun recover(
        sessionId: Long,
        files: List<RecoveredFileEntity>,
        destination: Uri?,
        preserveStructure: Boolean,
        skipDuplicates: Boolean,
        albumName: String,
        onProgress: suspend (RecoveryProgress) -> Unit
    ): RecoveryReport {
        val startedAt = System.currentTimeMillis()
        val writer = createWriter(destination, albumName.ifBlank { ROOT_FOLDER_NAME })
        val failures = mutableListOf<String>()
        val needsUntrash = mutableListOf<String>()
        var succeeded = 0
        var skipped = 0
        var bytes = 0L
        val usedNames = mutableSetOf<String>()

        files.forEachIndexed { index, entity ->
            coroutineContext.ensureActive()

            if (skipDuplicates && entity.isDuplicate) {
                skipped++
                return@forEachIndexed
            }

            val relativeFolder = if (preserveStructure) entity.folderPath else ""
            onProgress(
                RecoveryProgress(
                    current = index + 1,
                    total = files.size,
                    currentName = entity.displayName,
                    currentFolder = relativeFolder,
                    bytesWritten = bytes
                )
            )

            // عنصر في سلة مهملات النظام: يُستعاد بأمر إلغاء الحذف لا بالنسخ
            if (entity.note == "trashed" && !entity.contentUri.isNullOrBlank()) {
                needsUntrash += entity.contentUri
                return@forEachIndexed
            }

            val source = openSource(entity)
            if (source == null) {
                failures += "${entity.displayName}: تعذّر فتح الملف للقراءة"
                return@forEachIndexed
            }

            val name = uniqueName(entity, usedNames, relativeFolder)
            val result = runCatching {
                writer.write(
                    relativeFolder,
                    name,
                    entity.mimeType,
                    entity.mediaType == com.deeprecovery.pro.data.model.MediaType.VIDEO,
                    source
                )
            }
            result.onSuccess { written ->
                succeeded++
                bytes += written.bytes
                fileDao.markRecovered(entity.id, written.uri, System.currentTimeMillis())
            }.onFailure { error ->
                Log.w(TAG, "فشل استعادة ${entity.displayName}", error)
                failures += "${entity.displayName}: ${error.message ?: "خطأ غير معروف"}"
            }
        }

        val report = RecoveryReport(
            requested = files.size,
            succeeded = succeeded,
            failed = files.size - succeeded - skipped - needsUntrash.size,
            skippedDuplicates = skipped,
            foldersCreated = writer.foldersCreated,
            bytesWritten = bytes,
            destination = writer.destinationLabel,
            failures = failures,
            needsUntrash = needsUntrash
        )

        reportDao.insert(
            RecoveryReportEntity(
                sessionId = sessionId,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                destination = report.destination,
                requestedCount = report.requested,
                succeededCount = report.succeeded,
                failedCount = report.failed,
                skippedDuplicates = report.skippedDuplicates,
                foldersCreated = report.foldersCreated,
                bytesWritten = report.bytesWritten
            )
        )
        return report
    }

    /**
     * يفتح مقبض قراءة صالحاً للملف.
     *
     * على أندرويد 10+ يُحجب الوصول المباشر لمسار DATA، لذلك نجرّب content
     * Uri أولاً للملفات المكتشفة عبر MediaStore، ثم نعود إلى الملف على
     * القرص للملفات المنحوتة داخل مساحة التطبيق.
     */
    private fun openSource(entity: RecoveredFileEntity): (() -> java.io.InputStream)? {
        entity.contentUri?.let { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            if (uri != null && runCatching {
                    context.contentResolver.openInputStream(uri)?.close(); true
                }.getOrDefault(false)
            ) {
                return { context.contentResolver.openInputStream(uri)!! }
            }
        }

        val file = entity.stagedPath?.let(::File)
        if (file != null && file.exists() && file.canRead()) {
            return { FileInputStream(file) }
        }
        return null
    }

    /** يضمن عدم تعارض الأسماء داخل نفس المجلد الوجهة. */
    private fun uniqueName(
        entity: RecoveredFileEntity,
        used: MutableSet<String>,
        folder: String
    ): String {
        val base = entity.displayName.substringBeforeLast('.', entity.displayName)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifEmpty { "recovered_${entity.id}" }
        val ext = entity.extension.ifEmpty { "bin" }
        var candidate = "$base.$ext"
        var counter = 1
        while (!used.add("$folder/$candidate")) {
            candidate = "${base}_$counter.$ext"
            counter++
        }
        return candidate
    }

    // ------------------------------------------------------------ الكتابة

    private data class WriteResult(val uri: String, val bytes: Long)

    private interface DestinationWriter {
        val destinationLabel: String
        val foldersCreated: Int
        fun write(
            relativeFolder: String,
            name: String,
            mimeType: String,
            isVideo: Boolean,
            source: () -> java.io.InputStream
        ): WriteResult
    }

    /**
     * يختار طريقة الكتابة إلى الوجهة.
     *
     * الكتابة المباشرة إلى مجلد عام محجوبة على أندرويد ١٠+ ما لم يُمنح
     * إذن الوصول لكل الملفات، فكانت الاستعادة تفشل بصمت لكل ملف. لذلك
     * الوجهة الافتراضية الآن عبر MediaStore: تعمل بلا أي إذن تخزين
     * وتظهر الملفات في المعرض مباشرة.
     */
    private fun createWriter(destination: Uri?, albumName: String): DestinationWriter = when {
        destination != null -> SafWriter(context, destination)
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
            MediaStoreWriter(context, albumName)
        else ->
            DirectWriter(File(android.os.Environment.getExternalStorageDirectory(), albumName))
    }

    /**
     * كتابة عبر MediaStore — الوجهة الافتراضية على أندرويد ١٠+.
     *
     * لا تحتاج أي إذن تخزين، وتضع الملفات تحت `Pictures/DeepRecoveryPro`
     * أو `Movies/DeepRecoveryPro` مع الحفاظ على شجرة المجلدات الأصلية،
     * فتظهر في المعرض فور انتهاء الاستعادة.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private class MediaStoreWriter(
        private val context: Context,
        private val albumName: String
    ) : DestinationWriter {

        private val createdFolders = mutableSetOf<String>()

        override val destinationLabel: String = albumName
        override val foldersCreated: Int get() = createdFolders.size

        override fun write(
            relativeFolder: String,
            name: String,
            mimeType: String,
            isVideo: Boolean,
            source: () -> java.io.InputStream
        ): WriteResult {
            val baseDir = if (isVideo) {
                android.os.Environment.DIRECTORY_MOVIES
            } else {
                android.os.Environment.DIRECTORY_PICTURES
            }
            val relative = buildString {
                append(baseDir).append('/').append(albumName)
                val clean = sanitize(relativeFolder)
                if (clean.isNotEmpty()) append('/').append(clean)
            }
            createdFolders += relative

            val collection = if (isVideo) {
                android.provider.MediaStore.Video.Media
                    .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                android.provider.MediaStore.Images.Media
                    .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(
                    android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    mimeType.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" }
                )
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relative)
                // معلّق أثناء الكتابة حتى لا يقرأه المعرض ناقصاً
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(collection, values)
                ?: error("تعذّر إنشاء الملف في المعرض")

            var written = 0L
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            written += read
                        }
                        out.flush()
                    }
                } ?: error("تعذّر فتح مجرى الكتابة")
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }

            val done = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, done, null, null)

            return WriteResult(uri.toString(), written)
        }

        private fun sanitize(path: String): String = path.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { it.replace(Regex("[\\\\:*?\"<>|]"), "_") }
    }

    /**
     * كتابة عبر Storage Access Framework مع إنشاء شجرة المجلدات.
     * يخزّن المجلدات المُنشأة لتفادي إعادة البحث عنها لكل ملف.
     */
    private class SafWriter(
        private val context: Context,
        treeUri: Uri
    ) : DestinationWriter {

        private val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("وجهة الحفظ غير صالحة")

        private val cache = mutableMapOf<String, DocumentFile>()
        private var created = 0

        override val destinationLabel: String = root.name ?: treeUri.toString()
        override val foldersCreated: Int get() = created

        override fun write(
            relativeFolder: String,
            name: String,
            mimeType: String,
            isVideo: Boolean,
            source: () -> java.io.InputStream
        ): WriteResult {
            val dir = resolveFolder(relativeFolder)
            val existing = dir.findFile(name)
            existing?.delete()
            // النوع الصحيح مهم: بعض مزوّدي التخزين يشتقّون الامتداد منه،
            // فيخرج الملف باسم أو امتداد خاطئ إن أرسلنا نوعاً عاماً
            val target = dir.createFile(mimeType.ifBlank { "application/octet-stream" }, name)
                ?: error("تعذّر إنشاء الملف في الوجهة")

            var written = 0L
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                source().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                    }
                    out.flush()
                }
            } ?: error("تعذّر فتح مجرى الكتابة")

            return WriteResult(target.uri.toString(), written)
        }

        /** ينشئ شجرة المجلدات جزءاً جزءاً — هذا ما يُرجع المجلد كاملاً كما كان. */
        private fun resolveFolder(relativeFolder: String): DocumentFile {
            if (relativeFolder.isBlank()) return root
            cache[relativeFolder]?.let { return it }

            var current = root
            val segments = relativeFolder.split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }

            val accumulated = StringBuilder()
            for (segment in segments) {
                if (accumulated.isNotEmpty()) accumulated.append('/')
                accumulated.append(segment)
                val key = accumulated.toString()
                val cached = cache[key]
                if (cached != null) {
                    current = cached
                    continue
                }
                val safeName = segment.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val existing = current.findFile(safeName)?.takeIf { it.isDirectory }
                current = existing ?: (current.createDirectory(safeName)
                    ?.also { created++ }
                    ?: error("تعذّر إنشاء المجلد $safeName"))
                cache[key] = current
            }
            return current
        }
    }

    /** كتابة مباشرة إلى مجلد على التخزين (يتطلب صلاحية وصول كامل). */
    private class DirectWriter(private val root: File) : DestinationWriter {

        private var created = 0

        override val destinationLabel: String = root.absolutePath
        override val foldersCreated: Int get() = created

        override fun write(
            relativeFolder: String,
            name: String,
            mimeType: String,
            isVideo: Boolean,
            source: () -> java.io.InputStream
        ): WriteResult {
            val dir = if (relativeFolder.isBlank()) root else File(root, sanitize(relativeFolder))
            if (!dir.exists()) {
                if (dir.mkdirs()) created++ else error("تعذّر إنشاء المجلد ${dir.absolutePath}")
            }
            val target = File(dir, name)
            var written = 0L
            source().use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                    }
                    out.flush()
                }
            }
            return WriteResult(Uri.fromFile(target).toString(), written)
        }

        private fun sanitize(path: String): String = path.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { it.replace(Regex("[\\\\:*?\"<>|]"), "_") }
    }
}
