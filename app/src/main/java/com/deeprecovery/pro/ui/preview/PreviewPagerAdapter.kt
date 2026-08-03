package com.deeprecovery.pro.ui.preview

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.databinding.ItemPreviewPageBinding
import java.io.File

/**
 * صفحات المعاينة: صفحة كاملة لكل ملف مع تمرير أفقي بين النتائج.
 *
 * الفيديو لا يُشغَّل إلا في الصفحة المعروضة حالياً — تشغيل كل الصفحات
 * معاً يستهلك فاكّات الترميز ويوقف التطبيق.
 */
class PreviewPagerAdapter(
    private val onPlayVideo: (RecoveredFileEntity, PageHolder) -> Unit
) : ListAdapter<RecoveredFileEntity, PreviewPagerAdapter.PageHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val binding = ItemPreviewPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageHolder(binding)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) =
        holder.bind(getItem(position))

    inner class PageHolder(
        val binding: ItemPreviewPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecoveredFileEntity) = with(binding) {
            // Uri أولاً: المسار المباشر محجوب على أندرويد 10+
            val uri = item.recoveredUri?.let(Uri::parse)
                ?: item.contentUri?.let(Uri::parse)
                ?: item.stagedPath?.let(::File)?.takeIf { it.exists() }?.let(Uri::fromFile)

            pagePlayer.visibility = View.GONE
            pageImage.visibility = View.VISIBLE

            Glide.with(pageImage)
                .load(uri)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .error(R.drawable.ic_broken_image)
                .into(pageImage)

            val isVideo = item.mediaType == MediaType.VIDEO && uri != null
            pagePlayBadge.visibility = if (isVideo) View.VISIBLE else View.GONE
            root.setOnClickListener {
                if (isVideo) onPlayVideo(item, this@PageHolder)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecoveredFileEntity>() {
            override fun areItemsTheSame(a: RecoveredFileEntity, b: RecoveredFileEntity) =
                a.id == b.id

            override fun areContentsTheSame(a: RecoveredFileEntity, b: RecoveredFileEntity) =
                a == b
        }
    }
}
