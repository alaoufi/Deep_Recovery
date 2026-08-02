package com.deeprecovery.pro.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.db.FolderSummary
import com.deeprecovery.pro.databinding.ItemFolderBinding
import com.deeprecovery.pro.util.FormatUtils

/**
 * عرض النتائج مجمّعة بحسب المجلد الأصلي.
 *
 * كل صف يسمح بفتح المجلد أو **استعادته كاملاً بما فيه** بضغطة واحدة.
 */
class FolderAdapter(
    private val onOpen: (FolderSummary) -> Unit,
    private val onRecoverAll: (FolderSummary) -> Unit,
    private val onToggleSelect: (FolderSummary) -> Unit
) : ListAdapter<FolderSummary, FolderAdapter.FolderViewHolder>(DIFF) {

    private var selectedFolders: Set<String> = emptySet()

    fun submitSelection(folders: Set<String>) {
        selectedFolders = folders
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, item.folderPath in selectedFolders)
    }

    override fun onBindViewHolder(
        holder: FolderViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position).folderPath in selectedFolders)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class FolderViewHolder(
        private val binding: ItemFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FolderSummary, selected: Boolean) = with(binding) {
            val context = root.context
            folderPath.text = item.folderPath
            folderStats.text = context.getString(
                R.string.folder_breakdown,
                item.imageCount,
                item.videoCount
            )
            folderSize.text = context.getString(R.string.folder_files_count, item.fileCount) +
                " • " + FormatUtils.formatSize(context, item.totalBytes)

            root.setOnClickListener { onOpen(item) }
            root.setOnLongClickListener {
                onToggleSelect(item)
                true
            }
            folderCheck.setOnClickListener { onToggleSelect(item) }
            recoverFolderButton.setOnClickListener { onRecoverAll(item) }

            bindSelection(selected)
        }

        fun bindSelection(selected: Boolean) = with(binding) {
            folderCheck.isChecked = selected
            folderCard.strokeWidth = if (selected) 3 else 1
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<FolderSummary>() {
            override fun areItemsTheSame(oldItem: FolderSummary, newItem: FolderSummary) =
                oldItem.folderPath == newItem.folderPath

            override fun areContentsTheSame(oldItem: FolderSummary, newItem: FolderSummary) =
                oldItem == newItem
        }
    }
}
