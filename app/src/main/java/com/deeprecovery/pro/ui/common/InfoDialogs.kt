package com.deeprecovery.pro.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.deeprecovery.pro.R
import com.deeprecovery.pro.util.CrashReporter
import com.deeprecovery.pro.util.Diagnostics
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * حوارات التنويه والخصوصية.
 *
 * التنويه إلزامي في التطبيق: يوضّح أن نجاح الاستعادة العميقة يعتمد على
 * نوع الجهاز وإصدار Android والكتابة فوق البيانات ووجود Root.
 */
object InfoDialogs {

    fun showDisclaimer(context: Context, onAccepted: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setIcon(R.drawable.ic_info)
            .setPositiveButton(R.string.disclaimer_understood) { dialog, _ ->
                dialog.dismiss()
                onAccepted?.invoke()
            }
            .setCancelable(onAccepted == null)
            .show()
    }

    /**
     * يعرض تقرير آخر انهيار مع إمكانية مشاركته.
     *
     * التقرير محفوظ داخل الجهاز فقط، ولا يُرسل إلا إذا اختار المستخدم
     * مشاركته بنفسه.
     */
    fun showCrashReport(context: Context, onDismiss: (() -> Unit)? = null) {
        val report = CrashReporter.readReport(context)
        if (report.isBlank()) {
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.crash_log_empty)
                .setPositiveButton(R.string.report_done, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_body)
            .setIcon(R.drawable.ic_info)
            .setPositiveButton(R.string.crash_view) { _, _ -> showCrashDetails(context, report) }
            .setNeutralButton(R.string.crash_share) { _, _ -> shareCrashReport(context) }
            .setNegativeButton(R.string.crash_dismiss) { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener { onDismiss?.invoke() }
            .show()
    }

    private fun showCrashDetails(context: Context, report: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.crash_details_title)
            .setMessage(report.takeLast(6000))
            .setPositiveButton(R.string.crash_share) { _, _ -> shareCrashReport(context) }
            .setNegativeButton(R.string.report_done, null)
            .show()
    }

    private fun shareCrashReport(context: Context) {
        val file = CrashReporter.logFile(context)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_details_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.crash_share))
            )
        }
    }

    /**
     * تقرير تشخيصي يُظهر ما يستطيع التطبيق رؤيته فعلاً.
     *
     * حين لا يجد الفحص شيئاً، هذا هو الفرق بين تخمين السبب ومعرفته:
     * إذن ناقص، أو مجلد غير موجود، أو النظام يمنع سرد محتواه.
     */
    fun showDiagnostics(context: Context) {
        val report = runCatching { Diagnostics.collect(context) }.getOrNull()
        if (report == null) {
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.diagnostics_failed)
                .setPositiveButton(R.string.report_done, null)
                .show()
            return
        }

        val text = Diagnostics.format(context, report)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.diagnostics_title)
            .setMessage(text)
            .setIcon(R.drawable.ic_info)
            .setPositiveButton(R.string.report_done, null)
            .setNeutralButton(R.string.crash_share) { _, _ -> shareText(context, text) }
            .show()
    }

    private fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_title))
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.crash_share))
            )
        }
    }

    fun showPrivacy(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_body)
            .setIcon(R.drawable.ic_shield)
            .setPositiveButton(R.string.report_done, null)
            .show()
    }
}
