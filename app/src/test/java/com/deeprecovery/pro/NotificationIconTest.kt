package com.deeprecovery.pro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * حارس ضد الخطأ الذي كان يقتل التطبيق أثناء الفحص.
 *
 * أيقونة الإشعار يفكّها نظام الإشعارات في سياقه هو، لا في سياق التطبيق.
 * فإذا احتوت على سمة ثيم مثل `?attr/colorControlNormal` فشل فكّها ورمى
 * النظام `Bad notification for startForeground` وقتل العملية — وهو ما
 * يظهر للمستخدم كرسالة «التطبيق مستمر في التوقف».
 */
class NotificationIconTest {

    /**
     * دليل الوحدة لا يعتمد على مجلد التشغيل: نصعد من المجلد الحالي حتى
     * نجد المجلد الذي يحتوي `src/main/res`، وإلا نجرّب المجلد الفرعي `app`.
     */
    private val moduleDir: File = findModuleDir()

    private val drawableDir = File(moduleDir, "src/main/res/drawable")
    private val workDir = File(moduleDir, "src/main/java/com/deeprecovery/pro/work")

    private fun findModuleDir(): File {
        var current: File? = File(".").absoluteFile.normalize()
        while (current != null) {
            if (File(current, "src/main/res/drawable").isDirectory) return current
            val appModule = File(current, "app/src/main/res/drawable")
            if (appModule.isDirectory) return File(current, "app")
            current = current.parentFile
        }
        error("تعذّر تحديد مجلد الوحدة انطلاقاً من ${File(".").absolutePath}")
    }

    @Test
    fun `أيقونات الإشعار خالية من سمات الثيم`() {
        val icons = drawableDir.listFiles { file -> file.name.startsWith("ic_notification_") }
        assertTrue("لا توجد أيقونات إشعار مخصّصة", !icons.isNullOrEmpty())

        val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        icons!!.forEach { icon ->
            // نفحص الوسوم الفعلية فقط، لا التعليقات التوضيحية
            val markup = comments.replace(icon.readText(), "")
            assertFalse(
                "${icon.name} يحتوي على سمة ثيم — ستقتل العملية عند نشر الإشعار",
                markup.contains("?attr/") || markup.contains("?android:attr/")
            )
        }
    }

    @Test
    fun `العمّال يستخدمون أيقونات الإشعار المخصّصة فقط`() {
        val workers = workDir.listFiles { file -> file.name.endsWith(".kt") }
        assertTrue("لم يُعثر على ملفات العمّال", !workers.isNullOrEmpty())

        val smallIcon = Regex("""setSmallIcon\(R\.drawable\.(\w+)\)""")
        var found = 0

        workers!!.forEach { worker ->
            smallIcon.findAll(worker.readText()).forEach { match ->
                found++
                val name = match.groupValues[1]
                assertTrue(
                    "${worker.name}: أيقونة الإشعار '$name' ليست من أيقونات الإشعار المخصّصة",
                    name.startsWith("ic_notification_")
                )
            }
        }

        assertTrue("لم يُعثر على أي استدعاء setSmallIcon", found > 0)
    }
}
