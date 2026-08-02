package com.deeprecovery.pro.util

import android.content.Context
import com.deeprecovery.pro.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** تنسيق الأحجام والأوقات بشكل مناسب للواجهة العربية. */
object FormatUtils {

    private val arabicLocale = Locale("ar")

    fun formatSize(context: Context, bytes: Long): String {
        if (bytes <= 0) return context.getString(R.string.size_zero)
        val units = context.resources.getStringArray(R.array.size_units)
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        val formatted = if (index == 0) {
            value.toLong().toString()
        } else {
            String.format(arabicLocale, "%.1f", value)
        }
        return "$formatted ${units[index]}"
    }

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "00:00"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** الوقت المتبقي التقريبي للفحص. */
    fun formatRemaining(context: Context, millis: Long): String = when {
        millis <= 0 -> context.getString(R.string.eta_calculating)
        millis < 60_000 -> context.getString(
            R.string.eta_seconds,
            TimeUnit.MILLISECONDS.toSeconds(millis).toInt()
        )
        millis < 3_600_000 -> context.getString(
            R.string.eta_minutes,
            TimeUnit.MILLISECONDS.toMinutes(millis).toInt()
        )
        else -> context.getString(
            R.string.eta_hours,
            TimeUnit.MILLISECONDS.toHours(millis).toInt(),
            (TimeUnit.MILLISECONDS.toMinutes(millis) % 60).toInt()
        )
    }

    fun formatDateTime(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", arabicLocale).format(Date(millis))
}
