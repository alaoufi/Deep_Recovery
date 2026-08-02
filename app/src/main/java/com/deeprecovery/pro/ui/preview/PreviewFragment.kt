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
import com.deeprecovery.pro.data.model.MediaType
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
        viewLifecycleOwner.lifecycleScope.launch {
            val file = viewModel.load(fileId) ?: return@launch
            currentFile = file
            render(file)
        }

        binding.recoverThisButton.setOnClickListener {
            val file = currentFile ?: return@setOnClickListener
            RecoverySheet.forFiles(file.sessionId, listOf(file.id))
                .show(childFragmentManager, RecoverySheet.TAG)
        }
    }

    private fun render(file: RecoveredFileEntity) = with(binding) {
        previewName.text = file.displayName

        previewQuality.text = getString(file.quality.labelRes) +
            " — " + getString(R.string.confidence_label, file.confidence)
        previewQuality.setBackgroundColor(
            ContextCompat.getColor(requireContext(), qualityColor(file.quality))
        )

        // Uri أولاً: المسار المباشر محجوب على أندرويد 10+
        val uri = file.contentUri?.let(Uri::parse)
            ?: file.stagedPath?.let(::File)?.takeIf { it.exists() }?.let(Uri::fromFile)

        if (file.mediaType == MediaType.VIDEO && uri != null) {
            showVideo(uri)
        } else {
            showImage(uri)
        }

        buildInfoTable(file)
    }

    private fun showImage(source: Uri?) = with(binding) {
        playerView.visibility = View.GONE
        imagePreview.visibility = View.VISIBLE
        Glide.with(imagePreview)
            .load(source)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .error(R.drawable.ic_broken_image)
            .into(imagePreview)
    }

    private fun showVideo(source: Uri) = with(binding) {
        imagePreview.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val exoPlayer = ExoPlayer.Builder(requireContext()).build()
        player = exoPlayer
        playerView.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(source))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = false
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
        player?.release()
        player = null
        _binding = null
    }
}
