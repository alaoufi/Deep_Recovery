package com.deeprecovery.pro.ui.common

import android.content.Context
import com.deeprecovery.pro.R
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

    fun showPrivacy(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_body)
            .setIcon(R.drawable.ic_shield)
            .setPositiveButton(R.string.report_done, null)
            .show()
    }
}
