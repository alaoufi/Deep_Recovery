package com.deeprecovery.pro.ui.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.databinding.ItemMediaGridBinding
import com.deeprecovery.pro.util.FormatUtils
import java.io.File

/**
 * معرض النتائج: الصور والفيديوهات نفسها معروضة مباشرة كشبكة مصغّرات.
 *
 * المقصود من الاستعادة هو محتوى المجلد لا المجلد، لذلك هذا هو العرض
 * الافتراضي، ويبقى عرض المجلدات خياراً لمن يريد استعادة مجلد كامل دفعة
 * واحدة.
 */
class MediaGridAdapter(
    private val onClick: (RecoveredFileEntity) -> Unit,
    private val onToggleSelect: (RecoveredFileEntity) -> Unit
) : ListAdapter<RecoveredFileEntity, MediaGridAdapter.GridViewHolder>(DIFF) {

    private var selectedIds: Set<Long> = emptySet()

    fun submitSelection(ids: Set<Long>) {
        selectedIds = ids
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val binding = ItemMediaGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GridViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id in selectedIds)
    }

    override fun onBindViewHolder(
        holder: GridViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position).id in selectedIds)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class GridViewHolder(
        private val binding: ItemMediaGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecoveredFileEntity, selected: Boolean) = with(binding) {
            val isVideo = item.mediaType == MediaType.VIDEO
            gridVideoBadge.visibility = if (isVideo) View.VISIBLE else View.GONE

            if (isVideo && item.durationMs > 0) {
                gridDuration.visibility = View.VISIBLE
                gridDuration.text = FormatUtils.formatDuration(item.durationMs)
            } else {
                gridDuration.visibility = View.GONE
            }

            gridQualityStrip.setBackgroundColor(
                ContextCompat.getColor(root.context, qualityColor(item.quality))
            )

            // Uri أولاً: المسار المباشر محجوب على أندرويد 10+
            // الملف المستَرد أولاً: موقعه الجديد مضمون القراءة
            val source: Any? = item.recoveredUri?.let(Uri::parse)
                ?: item.contentUri?.let(Uri::parse)
                ?: item.stagedPath?.let(::File)

            // فحص الفحص نفسه أثبت أن هذه البقايا لا تُفكّ (الأبعاد صفر)،
            // فطلب مصغّرة منها يفشل حتماً وينتهي بأيقونة «ملف تالف» تملأ
            // الشبكة وتوحي بخلل في التطبيق. نعرض حالتها الحقيقية بدلها.
            val previewable = item.widthPx > 0 && item.heightPx > 0
            gridNoPreview.visibility = if (previewable) View.GONE else View.VISIBLE

            if (previewable) {
                Glide.with(gridThumbnail)
                    .load(source)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .placeholder(if (isVideo) R.drawable.ic_video else R.drawable.ic_image)
                    .error(R.drawable.ic_broken_image)
                    .centerCrop()
                    .into(gridThumbnail)
            } else {
                Glide.with(gridThumbnail).clear(gridThumbnail)
                gridThumbnail.setImageResource(
                    if (isVideo) R.drawable.ic_video else R.drawable.ic_image
                )
            }

            root.setOnClickListener { onClick(item) }
            root.setOnLongClickListener {
                onToggleSelect(item)
                true
            }
            gridCheck.setOnClickListener { onToggleSelect(item) }

            bindSelection(selected)
        }

        fun bindSelection(selected: Boolean) = with(binding) {
            gridCheck.isChecked = selected
            gridCard.isChecked = selected
            gridCard.strokeWidth = if (selected) 4 else 0
        }
    }

    private fun qualityColor(quality: RecoveryQuality): Int = when (quality) {
        RecoveryQuality.EXCELLENT -> R.color.quality_excellent
        RecoveryQuality.GOOD -> R.color.quality_good
        RecoveryQuality.POOR -> R.color.quality_poor
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<RecoveredFileEntity>() {
            override fun areItemsTheSame(a: RecoveredFileEntity, b: RecoveredFileEntity) =
                a.id == b.id

            override fun areContentsTheSame(a: RecoveredFileEntity, b: RecoveredFileEntity) =
                a == b
        }
    }
}
