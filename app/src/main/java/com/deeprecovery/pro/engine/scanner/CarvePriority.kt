package com.deeprecovery.pro.engine.scanner

/**
 * ترتيب مرشّحي النحت بحسب احتمال احتوائهم بقايا ملفات محذوفة.
 *
 * أعلى مصدر إنتاجية بلا Root هو ذاكرة الصور المصغّرة
 * (`DCIM/.thumbnails/.thumbdata*`): ملف ضخم يحوي مصغّرات كل الصور التي
 * فهرسها المعرض يوماً ما — **بما فيها المحذوفة**. تليها مجلدات المهملات
 * و `LOST.DIR` وذواكر التطبيقات.
 *
 * بلا هذا الترتيب يُستهلك سقف المرشّحين على أول ملفات يصادفها المرور —
 * وهي صور عادية سليمة — فلا يصل النحت إلى المصادر المفيدة أصلاً.
 */
object CarvePriority {

    /** درجة أولوية 0..100؛ الأعلى يُنحت أولاً. */
    fun of(absolutePath: String): Int {
        val lower = absolutePath.lowercase()
        val name = lower.substringAfterLast('/')
        return when {
            name.startsWith(".thumbdata") -> 100
            lower.contains("/.thumbnails/") -> 90
            lower.contains("/lost.dir/") -> 80
            lower.contains("/.trash") -> 75
            name.startsWith(".trashed-") || name.startsWith(".pending-") -> 70
            lower.contains("/cache/") -> 40
            name.startsWith(".") -> 30
            else -> 10
        }
    }
}
