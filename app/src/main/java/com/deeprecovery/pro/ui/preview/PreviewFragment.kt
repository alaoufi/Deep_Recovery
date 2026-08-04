package com.deeprecovery.pro.ui.preview

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
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
    private var playingHolder: PreviewPagerAdapter.PageHolder? = null
    private var pendingDeleteId: Long = -1L

    /** نتيجة نافذة الحذف التي يعرضها النظام للملفات التي لا يملكها التطبيق. */
    private val systemDelete = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val id = pendingDeleteId
        pendingDeleteId = -1L
        if (result.resultCode == android.app.Activity.RESULT_OK && id > 0) {
            viewModel.forgetRecord(id) { onDeleted(id) }
        }
    }

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

        binding.openExternallyButton.setOnClickListener {
            currentFile?.let(::openExternally)
        }

        binding.recoverThisButton.setOnClickListener {
            val file = currentFile ?: return@setOnClickListener
            RecoverySheet.forFiles(file.sessionId, listOf(file.id))
                .show(childFragmentManager, RecoverySheet.TAG)
        }

        binding.deleteThisButton.setOnClickListener {
            currentFile?.let(::confirmDeleteForever)
        }
    }

    /** الحذف النهائي لا رجعة فيه، فلا يبدأ إلا بتأكيد صريح. */
    private fun confirmDeleteForever(file: RecoveredFileEntity) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_forever_title)
            .setMessage(getString(R.string.preview_delete_body, file.displayName))
            .setIcon(R.drawable.ic_info)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.delete_forever_confirm) { _, _ -> runDeleteForever(file) }
            .show()
    }

    private fun runDeleteForever(file: RecoveredFileEntity) {
        releasePlayer()
        viewModel.deleteForever(file.id) { report ->
            when {
                report.needsConsent.isNotEmpty() -> {
                    // ملف لا يملكه التطبيق: النظام وحده يحذفه بموافقة صريحة
                    pendingDeleteId = file.id
                    requestSystemDelete(report.needsConsent)
                }

                report.wiped > 0 -> onDeleted(file.id)

                else -> toast(getString(R.string.preview_delete_failed))
            }
        }
    }

    private fun requestSystemDelete(uris: List<Uri>) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            toast(getString(R.string.preview_delete_failed))
            return
        }
        runCatching {
            val request = android.provider.MediaStore.createDeleteRequest(
                requireContext().contentResolver,
                uris
            )
            systemDelete.launch(
                androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build()
            )
        }.onFailure { toast(getString(R.string.preview_delete_failed)) }
    }

    /**
     * يزيل الصفحة من المعرض بعد الحذف.
     *
     * إبقاؤها معروضة بعد حذف بياناتها يترك المستخدم أمام صفحة لملف لم
     * يعد موجوداً.
     */
    private fun onDeleted(id: Long) {
        // الرد يصل من نطاق الـViewModel وقد تكون الشاشة أُغلقت قبله
        _binding ?: return
        toast(getString(R.string.preview_delete_done))
        val remaining = pagerAdapter.currentList.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            findNavController().popBackStack()
            return
        }
        val next = pagerAdapter.currentList.indexOfFirst { it.id == id }
            .coerceIn(0, remaining.lastIndex)
        pagerAdapter.submitList(remaining) {
            binding.previewPager.setCurrentItem(next, false)
            remaining.getOrNull(next)?.let {
                currentFile = it
                render(it)
            }
        }
    }

    private fun toast(message: String) {
        _binding?.let {
            com.google.android.material.snackbar.Snackbar
                .make(it.root, message, 4000)
                .show()
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

        // الفيديو قد يفشل داخلياً لأسباب ترميز، فالمخرج الخارجي متاح دائماً
        openExternallyButton.visibility =
            if (file.mediaType == com.deeprecovery.pro.data.model.MediaType.VIDEO) {
                View.VISIBLE
            } else {
                View.GONE
            }

        buildInfoTable(file)
    }

    /** يشغّل الفيديو داخل صفحته الحالية فقط. */
    private fun playVideo(file: RecoveredFileEntity, holder: PreviewPagerAdapter.PageHolder) {
        val uri = playableUri(file)
        if (uri == null) {
            showPlaybackFailure(holder, getString(R.string.preview_no_source))
            return
        }

        releasePlayer()
        val exoPlayer = ExoPlayer.Builder(requireContext()).build()
        player = exoPlayer
        playingHolder = holder

        holder.binding.pageImage.visibility = View.GONE
        holder.binding.pagePlayBadge.visibility = View.GONE
        holder.binding.pagePlayer.visibility = View.VISIBLE
        holder.binding.pagePlayer.player = exoPlayer

        // بلا هذا المستمع يفشل التشغيل إلى شاشة سوداء صامتة: المستخدم يرى
        // «الفيديو لا يعمل» بلا أي سبب، ونحن نفقد الخطأ تماماً.
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                showPlaybackFailure(holder, error.errorCodeName)
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun playableUri(file: RecoveredFileEntity): Uri? =
        file.recoveredUri?.let(Uri::parse)
            ?: file.contentUri?.let(Uri::parse)
            ?: file.stagedPath?.let(::File)?.takeIf { it.exists() }?.let(Uri::fromFile)

    /**
     * يعيد الصورة المصغّرة ويشرح سبب الفشل بدل ترك شاشة سوداء.
     *
     * بقايا الملفات المستخرجة كثيراً ما تحمل ترميزاً لا يدعمه المشغّل
     * الداخلي بينما يفتحها مشغّل النظام، لذلك نعرض المخرج الخارجي هنا.
     */
    private fun showPlaybackFailure(holder: PreviewPagerAdapter.PageHolder, reason: String) {
        holder.binding.pagePlayer.visibility = View.GONE
        holder.binding.pageImage.visibility = View.VISIBLE
        holder.binding.pagePlayBadge.visibility = View.VISIBLE
        releasePlayer()
        _binding?.let { bound ->
            bound.openExternallyButton.visibility = View.VISIBLE
            com.google.android.material.snackbar.Snackbar
                .make(bound.root, getString(R.string.preview_play_failed, reason), 6000)
                .show()
        }
    }

    /** يسلّم الملف لمشغّل النظام — يدعم ترميزات لا يدعمها المشغّل الداخلي. */
    private fun openExternally(file: RecoveredFileEntity) {
        val uri = externalViewUri(file) ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType.ifBlank { "video/*" })
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val started = runCatching { startActivity(intent); true }.getOrDefault(false)
        if (!started) {
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, R.string.preview_no_external_app, 4000)
                .show()
        }
    }

    /**
     * `file://` يرفضه النظام في النوايا الخارجية منذ أندرويد ٧،
     * فنمرّ عبر FileProvider حين يكون المصدر ملفاً على القرص.
     */
    private fun externalViewUri(file: RecoveredFileEntity): Uri? {
        file.recoveredUri?.let { return Uri.parse(it) }
        file.contentUri?.let { return Uri.parse(it) }
        val onDisk = file.stagedPath?.let(::File)?.takeIf { it.exists() } ?: return null
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                onDisk
            )
        }.getOrNull()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        // الصفحة المغادَرة كانت تبقى على مشغّل فارغ أسود بلا صورة
        playingHolder?.binding?.let { page ->
            page.pagePlayer.player = null
            page.pagePlayer.visibility = View.GONE
            page.pageImage.visibility = View.VISIBLE
        }
        playingHolder = null
    }

    private fun buildInfoTable(file: RecoveredFileEntity) {
        val table = binding.infoTable
        table.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        fun row(labelRes: Int, value: String) {
            if (value.isBlank()) return
            val rowBinding = com.deeprecovery.pro.databinding.ItemInfoRowBinding
                .inflate(inflater, table, false)
            rowBinding.infoLabel.text = getString(labelRes)
            rowBinding.infoValue.text = value
            table.addView(rowBinding.root)
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
