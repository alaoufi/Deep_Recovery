package com.deeprecovery.pro.util

import android.content.Context
import androidx.core.content.edit
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanMode

/** تفضيلات محلية فقط — لا تُرسل أي بيانات خارج الجهاز. */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("deep_recovery_prefs", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /**
     * ترحيل التفضيلات المحفوظة.
     *
     * تغيير القيمة الافتراضية لا يكفي: من ثبّت إصداراً سابقاً يحمل
     * "فحص عميق" محفوظاً في جهازه، فيبقى الفحص يمرّ على التخزين كاملاً
     * ويستغرق عشرات الدقائق رغم تغيير الافتراضي. نعيد ضبطه مرة واحدة.
     */
    private fun migrate() {
        val version = prefs.getInt(KEY_PREFS_VERSION, 1)
        if (version < CURRENT_PREFS_VERSION) {
            prefs.edit {
                putString(KEY_DEPTH, ScanDepth.QUICK.name)
                putInt(KEY_PREFS_VERSION, CURRENT_PREFS_VERSION)
            }
        }
    }

    var scanMode: ScanMode
        get() = ScanMode.fromName(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    /** الافتراضي: فحص موجّه لأماكن المحذوف — سريع ومفيد. */
    var scanDepth: ScanDepth
        get() = ScanDepth.fromName(prefs.getString(KEY_DEPTH, ScanDepth.QUICK.name))
        set(value) = prefs.edit { putString(KEY_DEPTH, value.name) }

    var includeImages: Boolean
        get() = prefs.getBoolean(KEY_IMAGES, true)
        set(value) = prefs.edit { putBoolean(KEY_IMAGES, value) }

    var includeVideos: Boolean
        get() = prefs.getBoolean(KEY_VIDEOS, true)
        set(value) = prefs.edit { putBoolean(KEY_VIDEOS, value) }

    var selectedLocations: Set<String>
        get() = prefs.getStringSet(KEY_LOCATIONS, DEFAULT_LOCATIONS) ?: DEFAULT_LOCATIONS
        set(value) = prefs.edit { putStringSet(KEY_LOCATIONS, value) }

    /**
     * مجلدات اختارها المستخدم بنفسه.
     *
     * عند وجود أي مجلد هنا يقتصر الفحص عليه — وهذا يجعله سريعاً ومحصوراً
     * فيما يريده المستخدم بالضبط بدل الأماكن الجاهزة.
     */
    var customFolders: Set<String>
        get() = prefs.getStringSet(KEY_CUSTOM_FOLDERS, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_CUSTOM_FOLDERS, value) }

    fun addCustomFolder(path: String) {
        customFolders = customFolders + path
    }

    fun removeCustomFolder(path: String) {
        customFolders = customFolders - path
    }

    /** تجاهل مجلدات لقطات الشاشة وتسجيل الشاشة. */
    var ignoreScreenshots: Boolean
        get() = prefs.getBoolean(KEY_IGNORE_SCREENSHOTS, true)
        set(value) = prefs.edit { putBoolean(KEY_IGNORE_SCREENSHOTS, value) }

    /** وجهة الحفظ المختارة عبر Storage Access Framework. */
    var recoveryTreeUri: String?
        get() = prefs.getString(KEY_TREE_URI, null)
        set(value) = prefs.edit { putString(KEY_TREE_URI, value) }

    /** الحفاظ على شجرة المجلدات الأصلية عند الاستعادة. */
    var preserveFolderStructure: Boolean
        get() = prefs.getBoolean(KEY_PRESERVE_TREE, true)
        set(value) = prefs.edit { putBoolean(KEY_PRESERVE_TREE, value) }

    var skipDuplicates: Boolean
        get() = prefs.getBoolean(KEY_SKIP_DUPES, true)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_DUPES, value) }

    var lastSessionId: Long
        get() = prefs.getLong(KEY_LAST_SESSION, -1L)
        set(value) = prefs.edit { putLong(KEY_LAST_SESSION, value) }

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER, false)
        set(value) = prefs.edit { putBoolean(KEY_DISCLAIMER, value) }

    private companion object {
        const val KEY_PREFS_VERSION = "prefs_version"
        const val CURRENT_PREFS_VERSION = 2

        const val KEY_MODE = "scan_mode"
        const val KEY_DEPTH = "scan_depth"
        const val KEY_IMAGES = "include_images"
        const val KEY_VIDEOS = "include_videos"
        const val KEY_LOCATIONS = "locations"
        const val KEY_CUSTOM_FOLDERS = "custom_folders"
        const val KEY_IGNORE_SCREENSHOTS = "ignore_screenshots"
        const val KEY_TREE_URI = "recovery_tree_uri"
        const val KEY_PRESERVE_TREE = "preserve_tree"
        const val KEY_SKIP_DUPES = "skip_duplicates"
        const val KEY_LAST_SESSION = "last_session"
        const val KEY_DISCLAIMER = "disclaimer_accepted"

        val DEFAULT_LOCATIONS = setOf("dcim", "camera", "whatsapp", "downloads")
    }
}
