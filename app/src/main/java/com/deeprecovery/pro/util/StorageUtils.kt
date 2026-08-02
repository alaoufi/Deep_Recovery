package com.deeprecovery.pro.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.deeprecovery.pro.data.model.ScanLocation
import java.io.File

/** مجلد فحص فعلي على الجهاز. */
data class StorageTarget(
    val location: ScanLocation,
    val label: String,
    val directories: List<File>,
    val available: Boolean
) {
    val totalBytes: Long
        get() = directories.sumOf { runCatching { it.totalSpace }.getOrDefault(0L) }
}

/**
 * اكتشاف مواقع التخزين على الجهاز: الذاكرة الداخلية، بطاقة SD،
 * ومجلدات الوسائط المعروفة (DCIM / Camera / WhatsApp / Telegram / Downloads).
 */
object StorageUtils {

    private val WHATSAPP_ROOTS = listOf(
        "WhatsApp/Media",
        "Android/media/com.whatsapp/WhatsApp/Media",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"
    )

    private val TELEGRAM_ROOTS = listOf(
        "Telegram",
        "Android/media/org.telegram.messenger/Telegram",
        "Android/media/org.telegram.messenger.web/Telegram"
    )

    fun internalRoot(): File = Environment.getExternalStorageDirectory()

    /** أقسام التخزين الخارجية المتاحة للتطبيق (تشمل بطاقة SD إن وُجدت). */
    fun externalVolumes(context: Context): List<File> =
        ContextCompat.getExternalFilesDirs(context, null)
            .filterNotNull()
            .mapNotNull { appDir ->
                // /storage/XXXX-XXXX/Android/data/<pkg>/files → /storage/XXXX-XXXX
                var current: File? = appDir
                repeat(4) { current = current?.parentFile }
                current
            }
            .filter { it.exists() }

    fun sdCardRoot(context: Context): File? {
        val internal = internalRoot().absolutePath
        return externalVolumes(context).firstOrNull { it.absolutePath != internal }
    }

    /** يبني قائمة الأهداف القابلة للفحص بحسب ما هو موجود فعلاً على الجهاز. */
    fun resolveTargets(context: Context): List<StorageTarget> {
        val root = internalRoot()
        val sd = sdCardRoot(context)
        val roots = listOfNotNull(root, sd)

        fun dirsOf(vararg relative: String): List<File> =
            roots.flatMap { base -> relative.map { File(base, it) } }.filter { it.isDirectory }

        fun target(
            location: ScanLocation,
            label: String,
            dirs: List<File>
        ) = StorageTarget(location, label, dirs, dirs.isNotEmpty())

        return listOf(
            target(
                ScanLocation.INTERNAL_STORAGE,
                root.absolutePath,
                listOf(root).filter { it.isDirectory }
            ),
            target(
                ScanLocation.SD_CARD,
                sd?.absolutePath ?: "-",
                listOfNotNull(sd).filter { it.isDirectory }
            ),
            target(ScanLocation.DCIM, "DCIM", dirsOf("DCIM")),
            target(ScanLocation.CAMERA, "DCIM/Camera", dirsOf("DCIM/Camera", "DCIM/100ANDRO")),
            target(
                ScanLocation.WHATSAPP,
                "WhatsApp Media",
                dirsOf(*WHATSAPP_ROOTS.toTypedArray())
            ),
            target(
                ScanLocation.TELEGRAM,
                "Telegram Media",
                dirsOf(*TELEGRAM_ROOTS.toTypedArray())
            ),
            target(ScanLocation.DOWNLOADS, "Download", dirsOf("Download", "Downloads")),
            target(
                ScanLocation.PRIVATE,
                "Private / Safe",
                privateFolders(roots)
            )
        )
    }

    /** مساحة العمل المؤقتة للملفات المستخرجة (داخل مساحة التطبيق فقط). */
    fun stagingDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "carved").apply { mkdirs() }

    /** مجلد الاستعادة الافتراضي عند عدم اختيار وجهة عبر SAF. */
    fun defaultRecoveryDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "DeepRecoveryPro"
        )

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun mediaStoreImageUri() = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    fun mediaStoreVideoUri() = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun freeSpace(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    /** مجلدات صور واتساب. */
    val WHATSAPP_IMAGE_DIR_NAMES = setOf(
        "whatsapp images", "whatsapp image", "wa images"
    )

    /** مجلدات فيديو واتساب. */
    val WHATSAPP_VIDEO_DIR_NAMES = setOf(
        "whatsapp video", "whatsapp videos", "wa video"
    )

    /**
     * المجلدات الخاصة/الآمنة لدى المصنّعين.
     *
     * لكل مصنّع مساره الخاص لخزنة الملفات، ولا يظهر في مجلدات الوسائط
     * المعتادة، فلا يصله الفحص ما لم نستهدفه صراحةً.
     */
    val PRIVATE_DIR_PATHS = listOf(
        // هواوي
        ".privateProtect",
        ".safebox",
        "Android/data/com.huawei.hidisk/files/safebox",
        ".Huawei_safe",
        // شاومي
        "MIUI/privacy",
        ".privacy_safe",
        // فيفو
        ".vivo_hide",
        ".VivoHiddenFiles",
        // أوبو / ريلمي
        ".privacy",
        ".oppo_privacy",
        // أسماء شائعة
        "Private",
        ".private",
        ".SecureFolder",
        ".gallery_lock",
        ".lockedFolder",
        ".hidden"
    )

    /** المجلدات الخاصة الموجودة فعلاً على الجهاز. */
    fun privateFolders(volumes: List<File>): List<File> =
        volumes
            .flatMap { root -> PRIVATE_DIR_PATHS.map { File(root, it) } }
            .filter { it.isDirectory }

    /** مجلدات لقطات الشاشة وتسجيلها — يُستثنى عادةً من الاستعادة. */
    val SCREENSHOT_DIR_NAMES = setOf(
        "screenshots", "screenshot", "screen recordings", "screenrecorder",
        "screenrecord", "screen_recordings", "capture", "screencapture"
    )

    /**
     * يحوّل عنوان شجرة من Storage Access Framework إلى مسار حقيقي.
     *
     * يمكّن المستخدم من اختيار أي مجلد على الجهاز بنفسه — بما فيها
     * المجلدات الخاصة — بدل الاقتصار على أماكن جاهزة.
     * يعيد null إذا كان العنوان لا يقابل مساراً على التخزين.
     */
    fun pathFromTreeUri(
        treeDocumentId: String,
        internalRootPath: String = internalRoot().absolutePath
    ): String? {
        val parts = treeDocumentId.split(':', limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        val relative = parts[1].trim('/')

        val base = when {
            volume.equals("primary", ignoreCase = true) -> internalRootPath
            volume.isNotEmpty() -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /** مجلدات الوسائط التي يضع المستخدم صوره وفيديوهاته فيها. */
    private val MEDIA_DIRS = listOf(
        "DCIM", "Pictures", "Movies", "Download", "Downloads", "Camera"
    )

    /** المجلدات التي تحتفظ ببقايا المحذوف. */
    private val TRASH_DIRS = listOf(
        "LOST.DIR", ".Trash", ".trash", ".thumbnails", ".Trash-1000"
    )

    /**
     * أماكن بقايا الملفات المحذوفة فقط.
     *
     * المرور على التخزين كاملاً يعني عشرات آلاف الملفات — أغلبها بيانات
     * تطبيقات لا علاقة لها بالوسائط — فيستغرق الفحص عشرات الدقائق بلا
     * فائدة. أداة الاستعادة يجب أن تفحص ما قد يحتوي محذوفاً فقط:
     * مجلدات الوسائط، وسلال المهملات، و LOST.DIR، وذاكرة المصغّرات.
     */
    fun recoveryHotspots(volumes: List<File>): List<File> =
        dedupeOverlappingPaths(
            volumes
                .flatMap { hotspotPaths(it.absolutePath) }
                .filter { File(it).isDirectory }
        ).map(::File)

    /** المسارات المرشّحة تحت جذر تخزين واحد — منفصلة لتكون قابلة للاختبار. */
    fun hotspotPaths(root: String): List<String> {
        val base = root.trimEnd('/')
        val out = mutableListOf<String>()
        TRASH_DIRS.forEach { out += "$base/$it" }
        MEDIA_DIRS.forEach { media ->
            out += "$base/$media"
            TRASH_DIRS.forEach { out += "$base/$media/$it" }
        }
        WHATSAPP_ROOTS.forEach { out += "$base/$it" }
        TELEGRAM_ROOTS.forEach { out += "$base/$it" }
        // المجلدات الخاصة لا تصلها مجلدات الوسائط المعتادة
        PRIVATE_DIR_PATHS.forEach { out += "$base/$it" }
        return out
    }

    /**
     * يزيل المسارات المتداخلة ويُبقي الجذر الأعلى فقط.
     *
     * اختيار «الذاكرة الداخلية» مع DCIM و Camera معاً يعني أن الملف نفسه
     * يقع تحت أكثر من جذر مختار، فيُفحص عدة مرات ويظهر مكرراً في النتائج
     * ويطيل الفحص أضعافاً.
     */
    fun dedupeOverlappingPaths(paths: List<String>): List<String> {
        val kept = mutableListOf<String>()
        paths.map { it.trimEnd('/') }
            .distinct()
            .sortedBy { it.length }
            .forEach { path ->
                val covered = kept.any { path == it || path.startsWith("$it/") }
                if (!covered) kept += path
            }
        return kept
    }

    /** يحذف مجلد الملفات المؤقتة بالكامل. */
    fun clearStaging(context: Context) {
        runCatching { stagingDir(context).deleteRecursively() }
        stagingDir(context)
    }
}
