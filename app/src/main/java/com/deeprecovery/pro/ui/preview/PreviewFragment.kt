package com.deeprecovery.pro.ui.preview

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.databinding.FragmentPreviewBinding
import com.deeprecovery.pro.ui.recovery.RecoverySheet
import com.deeprecovery.pro.util.FormatUtils
import kotlinx.coroutines.launch
import java.io.File

/**
 * معاينة قبل الاستعادة: عرض الصورة أو تشغيل الفيديو مع تفاصيل الملف
 * ومصدره وإزاحته داخل البيانات الخام.
 */
class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PreviewViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var currentFile: RecoveredFileEntity? = null
    private lateinit var pagerAdapter: PreviewPagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fileId = arguments?.getLong("fileId", -1L) ?: -1L

        pagerAdapter = PreviewPagerAdapter { file, holder -> playVideo(file, holder) }
        binding.previewPager.adapter = pagerAdapter
        binding.previewPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    // فيديو صفحة سابقة يجب أن يتوقف عند مغادرتها
                    releasePlayer()
                    pagerAdapter.currentList.getOrNull(position)?.let {
                        currentFile = it
                        render(it)
                    }
                }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val file = viewModel.load(fileId) ?: return@launch
            currentFile = file
            render(file)

            // معرض كامل: كل نتائج الجلسة قابلة للتمرير من نفس الشاشة
            val siblings = viewModel.loadSiblings(file.sessionId)
            val list = siblings.ifEmpty { listOf(file) }
            pagerAdapter.submitList(list) {
                val index = list.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                binding.previewPager.setCurrentItem(index, false)
                updateIndicator(index, list.size)
            }
        }

        binding.recoverThisButton.setOnClickListener {
            val file = currentFile ?: return@setOnClickListener
            RecoverySheet.forFiles(file.sessionId, listOf(file.id))
                .show(childFragmentManager, RecoverySheet.TAG)
        }
    }

    private fun updateIndicator(index: Int, total: Int) {
        binding.pageIndicator.text = getString(R.string.preview_position, index + 1, total)
    }

    private fun render(file: RecoveredFileEntity) = with(binding) {
        val position = pagerAdapter.currentList.indexOfFirst { it.id == file.id }
        if (position >= 0) updateIndicator(position, pagerAdapter.itemCount)

        previewName.text = file.displayName

        previewQuality.text = getString(file.quality.labelRes) +
            " — " + getString(R.string.confidence_label, file.confidence)
        previewQuality.setBackgroundColor(
            ContextCompat.getColor(requireContext(), qualityColor(file.quality))
        )

        buildInfoTable(file)
    }

    /** يشغّل الفيديو داخل صفحته الحالية فقط. */
    private fun playVideo(file: RecoveredFileEntity, holder: PreviewPagerAdapter.PageHolder) {
        val uri = file.contentUri?.let(Uri::parse)
            ?: file.stagedPath?.let(::File)?.takeIf { it.exists() }?.let(Uri::fromFile)
            ?: return

        releasePlayer()
        val exoPlayer = ExoPlayer.Builder(requireContext()).build()
        player = exoPlayer

        holder.binding.pageImage.visibility = View.GONE
        holder.binding.pagePlayBadge.visibility = View.GONE
        holder.binding.pagePlayer.visibility = View.VISIBLE
        holder.binding.pagePlayer.player = exoPlayer

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun buildInfoTable(file: RecoveredFileEntity) {
        val table = binding.infoTable
        table.removeAllViews()

        fun row(labelRes: Int, value: String) {
            if (value.isBlank()) return
            val tableRow = TableRow(requireContext())
            tableRow.addView(TextView(requireContext()).apply {
                text = getString(labelRes)
                setPadding(0, 6, 0, 6)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                )
            })
            tableRow.addView(TextView(requireContext()).apply {
                text = value
                setPadding(0, 6, 0, 6)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
            })
            table.addView(tableRow)
        }

        row(R.string.info_size, FormatUtils.formatSize(requireContext(), file.sizeBytes))
        row(R.string.info_type, "${file.extension.uppercase()} • ${file.mimeType}")
        if (file.widthPx > 0) row(R.string.info_dimensions, "${file.widthPx}×${file.heightPx}")
        if (file.durationMs > 0) {
            row(R.string.info_duration, FormatUtils.formatDuration(file.durationMs))
        }
        row(R.string.info_folder, file.folderPath)
        row(R.string.info_source, file.sourceName)
        if (file.sourceOffset >= 0) row(R.string.info_offset, file.sourceOffset.toString())
        row(R.string.info_signature, file.note)
        row(R.string.info_discovered, FormatUtils.formatDateTime(file.discoveredAt))
    }

    private fun qualityColor(quality: RecoveryQuality): Int = when (quality) {
        RecoveryQuality.EXCELLENT -> R.color.quality_excellent
        RecoveryQuality.GOOD -> R.color.quality_good
        RecoveryQuality.POOR -> R.color.quality_poor
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        _binding = null
    }
}
