package com.deeprecovery.pro.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanMode
import com.deeprecovery.pro.data.model.ScanStatus

/** جلسة فحص محفوظة — تسمح باستئناف الفحص وبفتح النتائج لاحقاً. */
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val mode: ScanMode,
    val depth: ScanDepth,
    val locations: String,
    val includeImages: Boolean,
    val includeVideos: Boolean,
    val status: ScanStatus,
    val totalBytes: Long = 0,
    val processedBytes: Long = 0,
    val filesFound: Int = 0,
    val imagesFound: Int = 0,
    val videosFound: Int = 0,
    val duplicatesFound: Int = 0,
    val errorMessage: String? = null
)

/**
 * تقدّم كل هدف فحص على حدة.
 *
 * هذا ما يجعل **الاستئناف** حقيقياً: عند الإيقاف نحفظ آخر إزاحة تمت
 * معالجتها لكل مصدر، فيبدأ الفحص التالي من نفس النقطة بدل البداية.
 */
@Entity(
    tableName = "scan_targets",
    indices = [Index("sessionId"), Index(value = ["sessionId", "sourceKey"], unique = true)]
)
data class ScanTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val sourceKey: String,
    val displayName: String,
    val locationKey: String,
    val totalBytes: Long,
    val processedBytes: Long = 0,
    val completed: Boolean = false
)

/**
 * ملف مكتشف.
 *
 * [folderPath] هو المجلد الأصلي الذي كان الملف بداخله (أو المجموعة التي
 * نُسبت إليه عند النحت). يُستخدم لعرض النتائج على شكل مجلدات ولاستعادة
 * **المجلد كاملاً بما فيه** مع الحفاظ على نفس الشجرة في وجهة الحفظ.
 */
@Entity(
    tableName = "recovered_files",
    indices = [
        Index("sessionId"),
        Index("contentHash"),
        Index("folderPath"),
        Index("mediaType")
    ]
)
data class RecoveredFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val mediaType: MediaType,
    val sizeBytes: Long,

    /** المجلد الأصلي (نسبي) — يُعاد بناؤه في وجهة الحفظ. */
    val folderPath: String,
    /** اسم مختصر للمجلد يظهر في الواجهة. */
    val folderLabel: String,

    /** وصف مصدر الاكتشاف: مسار الملف أو الجهاز الكتلي. */
    val sourceName: String,
    /** إزاحة البداية داخل المصدر الخام، أو -1 لملف موجود فعلاً. */
    val sourceOffset: Long,

    /** المسار المؤقت داخل مساحة التطبيق (أو المسار الأصلي للملفات غير المنحوتة). */
    val stagedPath: String?,
    val isCarved: Boolean,

    val quality: RecoveryQuality,
    val confidence: Int,
    val truncated: Boolean,
    val note: String,

    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val durationMs: Long = 0,

    val discoveredAt: Long,
    val contentHash: String? = null,
    val isDuplicate: Boolean = false,
    val duplicateOfId: Long? = null,

    val recovered: Boolean = false,
    val recoveredUri: String? = null,
    val recoveredAt: Long? = null
)

/** سجل عملية استعادة لعرض تقرير النجاح. */
@Entity(tableName = "recovery_reports")
data class RecoveryReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val destination: String,
    val requestedCount: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val skippedDuplicates: Int,
    val foldersCreated: Int,
    val bytesWritten: Long
)
