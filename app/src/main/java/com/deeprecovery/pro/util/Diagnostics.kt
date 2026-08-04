package com.deeprecovery.pro.util

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * تشخيص ما يستطيع التطبيق رؤيته فعلاً على هذا الجهاز.
 *
 * حين لا يجد الفحص شيئاً يصعب معرفة السبب: هل الإذن ناقص؟ أم المجلدات
 * غير موجودة؟ أم أن النظام يمنع سردها؟ هذا التقرير يحوّل "لا يعمل" إلى
 * حقائق قابلة للتصرف.
 */
object Diagnostics {

    data class FolderProbe(
        val path: String,
        val exists: Boolean,
        val listable: Boolean,
        val fileCount: Int
    )

    data class Report(
        val androidVersion: String,
        val device: String,
        val allFilesAccess: Boolean,
        val mediaPermission: Boolean,
        val trashedCount: Int,
        val thumbDataFiles: List<Pair<String, Long>>,
        val folders: List<FolderProbe>
    )

    fun collect(context: Context): Report {
        val volumes = buildList {
            add(StorageUtils.internalRoot())
            StorageUtils.sdCardRoot(context)?.let { add(it) }
        }

        val folders = StorageUtils.recoveryHotspots(volumes).map { probe(it) } +
            listOf(probe(StorageUtils.internalRoot()))

        return Report(
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            allFilesAccess = StorageUtils.hasAllFilesAccess(),
            mediaPermission = hasMediaPermission(context),
            trashedCount = countTrashed(context),
            thumbDataFiles = findThumbData(volumes),
            folders = folders.distinctBy { it.path }
        )
    }

    private fun probe(dir: File): FolderProbe {
        val exists = runCatching { dir.isDirectory }.getOrDefault(false)
        val children = if (exists) runCatching { dir.listFiles() }.getOrNull() else null
        return FolderProbe(
            path = dir.absolutePath,
            exists = exists,
            listable = children != null,
            fileCount = children?.size ?: 0
        )
    }

    private fun hasMediaPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** عدد العناصر في سلة مهملات النظام — أعلى مصدر استعادة مضمون. */
    private fun countTrashed(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var total = 0
        listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        ).forEach { collection ->
            val args = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            }
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    args,
                    null
                )?.use { total += it.count }
            }
        }
        return total
    }

    /**
     * ذاكرة المصغّرات: أعلى مصدر إنتاجية للاستعادة بلا Root.
     * وجودها من عدمه يفسّر مباشرة لماذا وجد الفحص شيئاً أو لم يجد.
     */
    private fun findThumbData(volumes: List<File>): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        val dirs = volumes.flatMap { root ->
            listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails")
                .map { File(root, it) }
        }
        dirs.forEach { dir ->
            runCatching { dir.listFiles() }.getOrNull()?.forEach { file ->
                if (file.isFile && file.length() > 0) {
                    out += file.absolutePath to file.length()
                }
            }
        }
        return out.sortedByDescending { it.second }.take(10)
    }

    /** نص جاهز للعرض والمشاركة. */
    fun format(context: Context, report: Report): String = buildString {
        appendLine("جهاز: ${report.device}")
        appendLine("أندرويد: ${report.androidVersion}")
        appendLine("إذن الوسائط: ${yesNo(report.mediaPermission)}")
        appendLine("الوصول لكل الملفات: ${yesNo(report.allFilesAccess)}")
        appendLine("عناصر في سلة مهملات النظام: ${report.trashedCount}")
        appendLine()

        appendLine("ذاكرة المصغّرات:")
        if (report.thumbDataFiles.isEmpty()) {
            appendLine("  لا توجد — وهذا يقلّل فرص الاستعادة بلا Root كثيراً")
        } else {
            report.thumbDataFiles.forEach { (path, size) ->
                appendLine("  ${path.substringAfterLast('/')} — ${FormatUtils.formatSize(context, size)}")
            }
        }
        appendLine()

        appendLine("المجلدات:")
        report.folders.forEach { folder ->
            val state = when {
                !folder.exists -> "غير موجود"
                !folder.listable -> "موجود لكن النظام يمنع سرده"
                else -> "${folder.fileCount} عنصر"
            }
            appendLine("  ${folder.path} — $state")
        }
    }

    private fun yesNo(value: Boolean) = if (value) "ممنوح" else "غير ممنوح"
}
