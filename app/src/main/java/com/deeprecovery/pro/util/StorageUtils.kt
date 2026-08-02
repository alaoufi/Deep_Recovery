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
            target(ScanLocation.DOWNLOADS, "Download", dirsOf("Download", "Downloads"))
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

    /** يحذف مجلد الملفات المؤقتة بالكامل. */
    fun clearStaging(context: Context) {
        runCatching { stagingDir(context).deleteRecursively() }
        stagingDir(context)
    }
}
