package com.deeprecovery.pro.engine.scanner

import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.engine.carver.MediaValidator
import com.deeprecovery.pro.engine.carver.SignatureRegistry
import com.deeprecovery.pro.util.StorageUtils
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * المرور على المجلدات المتاحة بحثاً عن ملفات وسائط مخفية أو مهملة.
 *
 * يغطي حالات شائعة جداً على أندرويد:
 *  - ملفات `.trashed-*` و `.pending-*` التي ينشئها النظام عند الحذف.
 *  - مجلدات `.thumbnails` و `.Trash` و `LOST.DIR`.
 *  - ملفات بامتداد مفقود أو خاطئ (نتعرف عليها بالتوقيع لا بالاسم).
 */
class FileSystemScanner {

    companion object {
        private val HIDDEN_PREFIXES = listOf(".trashed-", ".pending-", ".nomedia-")
        private val RECOVERY_DIRS = listOf(".Trash", ".trash", "LOST.DIR", ".thumbnails")
        private const val MAX_DEPTH = 12
    }

    /**
     * يمشي على [roots] ويستدعي [onFile] لكل ملف وسائط مرشّح.
     *
     * التعرّف يتم بقراءة أول بايتات الملف ومطابقتها مع سجل التواقيع،
     * وليس بالاعتماد على الامتداد.
     */
    suspend fun walk(
        roots: List<File>,
        includeImages: Boolean,
        includeVideos: Boolean,
        onProgressFile: suspend (File) -> Unit = {},
        onFile: suspend (DiscoveredFile) -> Unit
    ) {
        val signatures = SignatureRegistry.signaturesFor(includeImages, includeVideos)
        val header = ByteArray(SignatureRegistry.maxSignatureSpan + 8)
        val visited = mutableSetOf<String>()

        suspend fun visit(dir: File, depth: Int) {
            coroutineContext.ensureActive()
            if (depth > MAX_DEPTH) return
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
            if (!visited.add(canonical)) return
            // لا نفحص مساحة العمل المؤقتة الخاصة بالتطبيق
            if (canonical.contains("/Android/data/com.deeprecovery.pro")) return

            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                coroutineContext.ensureActive()
                when {
                    child.isDirectory -> visit(child, depth + 1)
                    child.isFile -> {
                        onProgressFile(child)
                        val discovered = inspect(child, signatures, header) ?: continue
                        onFile(discovered)
                    }
                }
            }
        }

        roots.forEach { root ->
            if (root.isDirectory) visit(root, 0)
        }
    }

    /** يفحص ملفاً واحداً ويقرر هل هو مرشّح للاستعادة. */
    private fun inspect(
        file: File,
        signatures: List<com.deeprecovery.pro.engine.carver.FileSignature>,
        header: ByteArray
    ): DiscoveredFile? {
        val length = file.length()
        if (length < 1024) return null

        val read = runCatching {
            file.inputStream().use { it.read(header, 0, header.size) }
        }.getOrDefault(-1)
        if (read <= 0) return null

        val signature = signatures.firstOrNull { it.matchesAt(header, 0, read) } ?: return null

        val name = file.name
        val looksDeleted = HIDDEN_PREFIXES.any { name.startsWith(it) } ||
            name.startsWith(".") ||
            file.parentFile?.name in RECOVERY_DIRS ||
            file.path.contains("/LOST.DIR/")

        // نتجاهل الملفات الحيّة العادية إلا إن كان امتدادها لا يطابق محتواها
        val extensionMismatch = !name.substringAfterLast('.', "")
            .equals(signature.extension, ignoreCase = true)
        if (!looksDeleted && !extensionMismatch) return null

        val validation = MediaValidator.validate(file, signature.mediaType)
        val confidence = when {
            validation.decodable && looksDeleted -> 95
            validation.decodable -> 85
            else -> 30
        }

        val cleanName = HIDDEN_PREFIXES.fold(name) { acc, prefix ->
            if (acc.startsWith(prefix)) acc.removePrefix(prefix).substringAfter('-') else acc
        }

        val parent = file.parentFile
        val folderPath = relativeFolder(parent)

        return DiscoveredFile(
            displayName = cleanName.ifEmpty { name },
            path = file.absolutePath,
            uri = null,
            mimeType = signature.mimeType,
            mediaType = signature.mediaType,
            sizeBytes = length,
            folderPath = folderPath,
            folderLabel = parent?.name ?: folderPath,
            width = validation.width,
            height = validation.height,
            durationMs = validation.durationMs,
            confidence = confidence,
            quality = RecoveryQuality.fromConfidence(confidence),
            note = if (looksDeleted) "deleted-marker" else "ext-mismatch"
        )
    }

    /** يحوّل المسار المطلق إلى مسار نسبي يُعاد بناؤه في وجهة الحفظ. */
    private fun relativeFolder(dir: File?): String {
        if (dir == null) return "Unknown"
        val root = StorageUtils.internalRoot().absolutePath
        val path = dir.absolutePath
        return when {
            path.startsWith(root) -> path.removePrefix(root).trim('/').ifEmpty { "Root" }
            path.startsWith("/storage/") -> path.removePrefix("/storage/").trim('/')
            else -> path.trim('/')
        }
    }
}

/** أنواع الوسائط المدعومة للعرض السريع في الواجهة. */
val MediaType.isPlayable: Boolean
    get() = this == MediaType.VIDEO
