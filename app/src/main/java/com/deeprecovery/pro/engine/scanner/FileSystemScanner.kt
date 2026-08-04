package com.deeprecovery.pro.engine.scanner

import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.engine.carver.FileSignature
import com.deeprecovery.pro.engine.carver.MediaValidator
import com.deeprecovery.pro.engine.carver.SignatureRegistry
import com.deeprecovery.pro.util.StorageUtils
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/** حصيلة مرحلة القياس المسبقة، تُستخدم لحساب نسبة إنجاز حقيقية. */
data class WalkMeasurement(
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val carveBytes: Long = 0
)

/**
 * نتيجة العدّ السريع للملفات.
 *
 * @param complete هل انتهى العدّ ضمن الميزانية؟ إن لم ينتهِ فالعدد حدّ
 *   أدنى لا إجمالي، ولا يصلح مقاماً لنسبة مئوية.
 */
data class FileCount(
    val files: Int = 0,
    val complete: Boolean = false
)

/**
 * المرور على المجلدات المتاحة بحثاً عن ملفات وسائط مخفية أو مهملة.
 *
 * يغطي حالات شائعة جداً على أندرويد:
 *  - ملفات `.trashed-*` و `.pending-*` التي ينشئها النظام عند الحذف.
 *  - مجلدات `.Trash` و `LOST.DIR` و `.thumbnails`.
 *  - ملفات بامتداد مفقود أو خاطئ (نتعرف عليها بالتوقيع لا بالاسم).
 */
class FileSystemScanner {

    companion object {
        private val HIDDEN_PREFIXES = listOf(".trashed-", ".pending-", ".nomedia-")
        private val RECOVERY_DIRS = listOf(".Trash", ".trash", "LOST.DIR", ".thumbnails")
        private const val MAX_DEPTH = 12
        private const val APP_DIR_MARKER = "/Android/data/com.deeprecovery.pro"

        /** أدنى أولوية موقع نقبل عندها ملفاً بلا امتداد — انظر [CarvePriority]. */
        private const val RECOVERY_LOCATION_PRIORITY = 30

        /**
         * امتدادات مكافئة لكل توقيع.
         *
         * بدونها يُعتبر كل ملف `.jpeg` مخالفاً لتوقيع `jpg` فيُدرج كمرشّح
         * استعادة — وهو إنذار كاذب يملأ النتائج ويبطئ الفحص.
         */
        private val EXTENSION_ALIASES: Map<String, Set<String>> = mapOf(
            "jpg" to setOf("jpg", "jpeg", "jpe", "jfif"),
            "png" to setOf("png"),
            "webp" to setOf("webp"),
            "heic" to setOf("heic", "heif", "hif"),
            "mp4" to setOf("mp4", "m4v", "mp4v"),
            "m4v" to setOf("m4v", "mp4"),
            "mov" to setOf("mov", "qt"),
            "3gp" to setOf("3gp", "3gpp"),
            "3g2" to setOf("3g2", "3gpp2"),
            "avi" to setOf("avi"),
            "mkv" to setOf("mkv", "webm")
        )
    }

    /**
     * مرور تكراري (بلا استدعاء ذاتي) على شجرة المجلدات.
     *
     * يتجاهل الروابط الدائرية عبر المسارات المعيارية، ولا يدخل مساحة عمل
     * التطبيق حتى لا يفحص ما استخرجه بنفسه.
     */
    private suspend fun traverse(
        roots: List<File>,
        excludedDirNames: Set<String> = emptySet(),
        onFile: suspend (File) -> Unit
    ) {
        val stack = ArrayDeque<Pair<File, Int>>()
        val visited = mutableSetOf<String>()
        // الاستثناء يشمل الجذور نفسها: مجلد مستبعَد قد يكون جذر فحص
        roots.forEach {
            if (it.isDirectory && it.name.lowercase() !in excludedDirNames) {
                stack.addLast(it to 0)
            }
        }

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue

            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(canonical)) continue
            if (canonical.contains(APP_DIR_MARKER)) continue

            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                coroutineContext.ensureActive()
                when {
                    child.isDirectory -> {
                        if (child.name.lowercase() !in excludedDirNames) {
                            stack.addLast(child to depth + 1)
                        }
                    }
                    child.isFile -> onFile(child)
                }
            }
        }
    }

    /**
     * مرحلة قياس سريعة: تعدّ الملفات وتجمع أحجامها بلا قراءة أي محتوى.
     *
     * هذه هي التي تعطي مقاماً حقيقياً لنسبة الإنجاز والوقت المتبقي؛ بدونها
     * تبقى النسبة صفراً ويبدو التطبيق معلّقاً.
     */
    suspend fun measure(
        roots: List<File>,
        minCarveBytes: Long,
        maxCarveFiles: Int
    ): WalkMeasurement {
        var fileCount = 0
        var totalBytes = 0L
        var carveBytes = 0L
        var carveFiles = 0

        traverse(roots) { file ->
            val length = runCatching { file.length() }.getOrDefault(0L)
            fileCount++
            totalBytes += length
            if (length >= minCarveBytes && carveFiles < maxCarveFiles) {
                carveFiles++
                carveBytes += length
            }
        }

        return WalkMeasurement(fileCount, totalBytes, carveBytes)
    }

    /**
     * عدّ سريع للملفات بلا قراءة أي محتوى ولا حتى حجم.
     *
     * هذا ما يعطي مقاماً حقيقياً لنسبة الإنجاز والوقت المتبقي. وهو رخيص
     * لأن الفحص صار موجّهاً لأماكن المحذوف؛ ومع ذلك نضع ميزانية زمنية
     * وسقفاً للعدد حتى لا يتحوّل هو نفسه إلى سبب بطء على المجلدات
     * الضخمة، فنكتفي حينها بمؤشر غير محدد.
     */
    suspend fun countFiles(
        roots: List<File>,
        excludedDirNames: Set<String> = emptySet(),
        budgetMs: Long = 6_000,
        maxFiles: Int = 300_000
    ): FileCount {
        val deadline = System.currentTimeMillis() + budgetMs
        var count = 0
        var complete = true

        runCatching {
            traverse(roots, excludedDirNames) { _ ->
                count++
                if (count >= maxFiles || System.currentTimeMillis() > deadline) {
                    complete = false
                    throw BudgetExceeded()
                }
            }
        }.onFailure { if (it !is BudgetExceeded) throw it }

        return FileCount(count, complete)
    }

    private class BudgetExceeded : RuntimeException()

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
        excludedDirNames: Set<String> = emptySet(),
        onProgressFile: suspend (File) -> Unit = {},
        onFile: suspend (DiscoveredFile) -> Unit
    ) {
        val signatures = SignatureRegistry.signaturesFor(includeImages, includeVideos)
        val header = ByteArray(SignatureRegistry.maxSignatureSpan + 8)

        traverse(roots, excludedDirNames) { file ->
            onProgressFile(file)
            inspect(file, signatures, header)?.let { onFile(it) }
        }
    }

    /** يفحص ملفاً واحداً ويقرر هل هو مرشّح للاستعادة. */
    private fun inspect(
        file: File,
        signatures: List<FileSignature>,
        header: ByteArray
    ): DiscoveredFile? {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length < 1024) return null

        val name = file.name
        val looksDeleted = HIDDEN_PREFIXES.any { name.startsWith(it) } ||
            name.startsWith(".") ||
            file.parentFile?.name in RECOVERY_DIRS ||
            file.path.contains("/LOST.DIR/")

        val actualExtension = name.substringAfterLast('.', "").lowercase()
        // ملف عادي بامتداد وسائط معروف ليس مرشّحاً — نتفاداه قبل أي قراءة
        val knownExtension = EXTENSION_ALIASES.values.any { actualExtension in it }
        if (!looksDeleted && knownExtension) return null

        // ملف بلا امتداد ليس دليل حذف بحد ذاته: ذواكر التطبيقات مليئة
        // بملفات مسمّاة ببصمة بلا امتداد وهي حيّة تماماً — مثل
        // `WhatsApp/.Shared`. كانت تُبتلع كلها كـ«مرشّحات استعادة» فتُغرق
        // النتائج. والأهم أن الرفض يجب أن يسبق فتح الملف: عشرات الآلاف
        // من عمليات الفتح والقراءة هي سبب البطء المباشر.
        if (!looksDeleted && actualExtension.isEmpty() &&
            CarvePriority.of(file.absolutePath) < RECOVERY_LOCATION_PRIORITY
        ) {
            return null
        }

        val read = runCatching {
            file.inputStream().use { it.read(header, 0, header.size) }
        }.getOrDefault(-1)
        if (read <= 0) return null

        val signature = signatures.firstOrNull { it.matchesAt(header, 0, read) } ?: return null

        val allowed = EXTENSION_ALIASES[signature.extension] ?: setOf(signature.extension)
        val extensionMismatch = actualExtension !in allowed
        if (!looksDeleted && !extensionMismatch) return null

        val validation = MediaValidator.validate(file, signature.mediaType)

        // صوت داخل حاوية MP4 ليس فيديو: يظهر بمدة صحيحة وبلا صورة،
        // وعند تشغيله يخرج صوت فقط. لا نعرضه كمقطع قابل للاستعادة.
        if (validation.audioOnly) return null
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
            note = if (looksDeleted) "deleted-marker" else "ext-mismatch",
            createdAt = runCatching { file.lastModified() }.getOrDefault(0L)
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
