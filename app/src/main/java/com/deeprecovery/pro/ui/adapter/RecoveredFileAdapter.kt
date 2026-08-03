package com.deeprecovery.pro.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.databinding.ItemRecoveredFileBinding
import com.deeprecovery.pro.util.FormatUtils
import java.io.File

/** عناصر شاشة النتائج: صورة مصغّرة، حجم، نوع، وحالة الاستعادة. */
class RecoveredFileAdapter(
    private val onClick: (RecoveredFileEntity) -> Unit,
    private val onToggleSelect: (RecoveredFileEntity) -> Unit
) : ListAdapter<RecoveredFileEntity, RecoveredFileAdapter.FileViewHolder>(DIFF) {

    private var selectedIds: Set<Long> = emptySet()

    fun submitSelection(ids: Set<Long>) {
        selectedIds = ids
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemRecoveredFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id in selectedIds)
    }

    override fun onBindViewHolder(
        holder: FileViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position).id in selectedIds)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class FileViewHolder(
        private val binding: ItemRecoveredFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecoveredFileEntity, selected: Boolean) = with(binding) {
            fileName.text = item.displayName
            folderName.text = item.folderPath

            val context = root.context
            val details = buildList {
                add(item.extension.uppercase())
                add(FormatUtils.formatSize(context, item.sizeBytes))
                if (item.widthPx > 0 && item.heightPx > 0) {
                    add("${item.widthPx}×${item.heightPx}")
                }
            }
            fileMeta.text = details.joinToString(" • ")

            qualityBadge.setText(item.quality.labelRes)
            qualityBadge.setBackgroundColor(
                ContextCompat.getColor(context, qualityColor(item.quality))
            )

            duplicateBadge.visibility = if (item.isDuplicate) View.VISIBLE else View.GONE
            truncatedBadge.visibility = if (item.truncated) View.VISIBLE else View.GONE

            if (item.mediaType == MediaType.VIDEO && item.durationMs > 0) {
                durationBadge.visibility = View.VISIBLE
                durationBadge.text = FormatUtils.formatDuration(item.durationMs)
            } else {
                durationBadge.visibility = View.GONE
            }

            val placeholder = when (item.mediaType) {
                MediaType.VIDEO -> R.drawable.ic_video
                MediaType.IMAGE -> R.drawable.ic_image
                MediaType.UNKNOWN -> R.drawable.ic_broken_image
            }

            // Uri أولاً: المسار المباشر محجوب على أندرويد 10+
            // الملف المستَرد أولاً: موقعه الجديد مضمون القراءة
            val source: Any? = item.recoveredUri?.let(Uri::parse)
                ?: item.contentUri?.let(Uri::parse)
                ?: item.stagedPath?.let(::File)
            Glide.with(thumbnail)
                .load(source)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(placeholder)
                .error(R.drawable.ic_broken_image)
                .centerCrop()
                .into(thumbnail)

            root.setOnClickListener { onClick(item) }
            root.setOnLongClickListener {
                onToggleSelect(item)
                true
            }
            selectCheck.setOnClickListener { onToggleSelect(item) }

            bindSelection(selected)
        }

        fun bindSelection(selected: Boolean) = with(binding) {
            selectCheck.isChecked = selected
            fileCard.isChecked = selected
            fileCard.strokeWidth = if (selected) 3 else 1
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
            override fun areItemsTheSame(
                oldItem: RecoveredFileEntity,
                newItem: RecoveredFileEntity
            ) = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: RecoveredFileEntity,
                newItem: RecoveredFileEntity
            ) = oldItem == newItem
        }
    }
}
