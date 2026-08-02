package com.deeprecovery.pro.data.model

import androidx.annotation.StringRes
import com.deeprecovery.pro.R

/** نوع الوسائط المكتشفة. */
enum class MediaType {
    IMAGE,
    VIDEO,
    UNKNOWN;

    companion object {
        fun fromName(value: String?): MediaType =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * جودة الاستعادة المقدّرة لكل ملف.
 *
 * التقييم مبني على: اكتمال بنية الملف (Header/Footer/Boxes)،
 * ونجاح فك الترميز الفعلي للصورة أو قراءة الميتاداتا للفيديو.
 */
enum class RecoveryQuality(
    val minConfidence: Int,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int
) {
    /** بنية كاملة + فك ترميز ناجح. */
    EXCELLENT(80, R.string.quality_excellent, R.string.quality_excellent_desc),

    /** بنية شبه كاملة، الملف يُفتح لكن قد ينقصه جزء. */
    GOOD(50, R.string.quality_good, R.string.quality_good_desc),

    /** بقايا ملف فقط، احتمال التلف مرتفع. */
    POOR(0, R.string.quality_poor, R.string.quality_poor_desc);

    companion object {
        fun fromConfidence(confidence: Int): RecoveryQuality = when {
            confidence >= EXCELLENT.minConfidence -> EXCELLENT
            confidence >= GOOD.minConfidence -> GOOD
            else -> POOR
        }

        fun fromName(value: String?): RecoveryQuality =
            entries.firstOrNull { it.name == value } ?: POOR
    }
}

/** وضع الفحص: عادي بدون Root أو متقدّم مع Root. */
enum class ScanMode(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    NORMAL(R.string.mode_normal, R.string.mode_normal_desc),
    ROOT(R.string.mode_root, R.string.mode_root_desc);

    companion object {
        fun fromName(value: String?): ScanMode =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

/** عمق الفحص، يتحكم في حجم البيانات المقروءة وسرعة العملية. */
enum class ScanDepth(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    /** فحص سريع: MediaStore + الملفات المهملة + المجلدات المعروفة. */
    QUICK(R.string.depth_quick, R.string.depth_quick_desc),

    /** فحص عميق: File Carving على الملفات والمساحات المتاحة. */
    DEEP(R.string.depth_deep, R.string.depth_deep_desc),

    /** فحص كامل: يشمل قراءة القطاعات الخام (يتطلب Root). */
    FULL(R.string.depth_full, R.string.depth_full_desc);

    companion object {
        fun fromName(value: String?): ScanDepth =
            entries.firstOrNull { it.name == value } ?: DEEP
    }
}

/** حالة جلسة الفحص. */
enum class ScanStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED

    companion object {
        fun fromName(value: String?): ScanStatus =
            entries.firstOrNull { it.name == value } ?: IDLE
    }
}

/** أماكن الفحص المتاحة. */
enum class ScanLocation(
    val key: String,
    @StringRes val labelRes: Int,
    val requiresRoot: Boolean = false
) {
    INTERNAL_STORAGE("internal", R.string.loc_internal),
    SD_CARD("sdcard", R.string.loc_sdcard),
    DCIM("dcim", R.string.loc_dcim),
    CAMERA("camera", R.string.loc_camera),
    WHATSAPP("whatsapp", R.string.loc_whatsapp),
    TELEGRAM("telegram", R.string.loc_telegram),
    DOWNLOADS("downloads", R.string.loc_downloads),
    RAW_BLOCK("raw_block", R.string.loc_raw_block, requiresRoot = true);

    companion object {
        fun fromKey(key: String): ScanLocation? = entries.firstOrNull { it.key == key }
    }
}
