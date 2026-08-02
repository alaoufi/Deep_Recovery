package com.deeprecovery.pro.ui.scan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanLocation
import com.deeprecovery.pro.data.model.ScanStatus
import com.deeprecovery.pro.databinding.FragmentScanBinding
import com.deeprecovery.pro.engine.scanner.ScanProgress
import com.deeprecovery.pro.engine.scanner.ScanRequest
import com.deeprecovery.pro.util.FormatUtils
import com.deeprecovery.pro.util.StorageUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * شاشة الفحص: نسبة الإنجاز، عدد الملفات المكتشفة، الوقت المتبقي،
 * مع إيقاف مؤقت واستئناف.
 */
class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScanViewModel by viewModels()
    private var started = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLabels()
        setupListeners()
        observe()

        if (savedInstanceState == null && !started) {
            started = true
            val resumeId = arguments?.getLong("resumeSessionId", -1L) ?: -1L
            viewModel.startScan(buildRequest(), resumeId)
        } else {
            viewModel.reattach()
        }
    }

    private fun setupLabels() = with(binding) {
        accessStatusText.setText(
            if (StorageUtils.hasAllFilesAccess()) {
                R.string.scan_access_full
            } else {
                R.string.scan_access_limited
            }
        )
        percentUnitText.setText(R.string.scan_files_scanned_unit)
        statFiles.statLabel.setText(R.string.scan_files_found)
        statScanned.statLabel.setText(R.string.scan_files_scanned)
        statImages.statLabel.setText(R.string.scan_images_found)
        statVideos.statLabel.setText(R.string.scan_videos_found)
        statFolders.statLabel.setText(R.string.scan_folders_found)
        progressCircle.max = 100
    }

    private fun buildRequest(): ScanRequest {
        val prefs = DeepRecoveryApp.instance.repository.prefs
        val locations = prefs.selectedLocations
            .mapNotNull { ScanLocation.fromKey(it) }
            .toMutableSet()

        val depth = prefs.scanDepth
        val mode = prefs.scanMode
        if (depth == ScanDepth.FULL && mode == com.deeprecovery.pro.data.model.ScanMode.ROOT) {
            locations += ScanLocation.RAW_BLOCK
        }

        return ScanRequest(
            mode = mode,
            depth = depth,
            locations = locations,
            includeImages = prefs.includeImages,
            includeVideos = prefs.includeVideos
        )
    }

    private fun setupListeners() = with(binding) {
        pauseResumeButton.setOnClickListener {
            if (viewModel.progress.value.status == ScanStatus.PAUSED) {
                viewModel.resume()
            } else {
                viewModel.pause()
            }
        }

        stopButton.setOnClickListener {
            viewModel.cancel()
            openResults()
        }

        viewResultsButton.setOnClickListener { openResults() }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.progress.collectLatest { render(it) } }
                launch {
                    viewModel.finishedSessionId.collectLatest { id ->
                        if (id > 0) binding.viewResultsButton.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun render(progress: ScanProgress) = with(binding) {
        // حجم ما سنمرّ عليه غير معروف مسبقاً في فحص المجلدات، فعرض نسبة
        // مئوية عليه تخمين يظهر جامداً على الصفر. نعرض بدلها مؤشراً حيّاً
        // مع عدّاد الملفات التي تم فحصها فعلاً.
        val indeterminate = progress.isIndeterminate && !progress.status.isTerminal
        if (progressCircle.isIndeterminate != indeterminate) {
            progressCircle.visibility = View.GONE
            progressCircle.isIndeterminate = indeterminate
            progressCircle.visibility = View.VISIBLE
        }

        if (indeterminate) {
            percentText.text = progress.filesScanned.toString()
            percentUnitText.visibility = View.VISIBLE
        } else {
            progressCircle.setProgressCompat(progress.percent, true)
            percentText.text = getString(R.string.scan_percent, progress.percent)
            percentUnitText.visibility = View.GONE
        }

        if (progress.stageLabelRes != 0) stageText.setText(progress.stageLabelRes)
        currentSourceText.text = progress.currentSource

        statScanned.statValue.text = progress.filesScanned.toString()
        statFiles.statValue.text = progress.filesFound.toString()
        statImages.statValue.text = progress.imagesFound.toString()
        statVideos.statValue.text = progress.videosFound.toString()
        statFolders.statValue.text = progress.foldersFound.toString()

        etaText.text = FormatUtils.formatRemaining(requireContext(), progress.etaMs)
        elapsedText.text = FormatUtils.formatDuration(progress.elapsedMs)

        val paused = progress.status == ScanStatus.PAUSED
        pausedHint.visibility = if (paused) View.VISIBLE else View.GONE
        pauseResumeButton.setText(if (paused) R.string.scan_resume else R.string.scan_pause)
        pauseResumeButton.setIconResource(
            if (paused) R.drawable.ic_play else R.drawable.ic_pause
        )

        if (progress.status.isTerminal) {
            pauseResumeButton.isEnabled = false
            stageText.setText(
                when (progress.status) {
                    ScanStatus.COMPLETED -> R.string.scan_completed
                    ScanStatus.CANCELLED -> R.string.scan_cancelled
                    else -> R.string.scan_failed
                }
            )
            viewResultsButton.visibility = View.VISIBLE
        }
    }

    private fun openResults() {
        val sessionId = viewModel.finishedSessionId.value
            .takeIf { it > 0 }
            ?: viewModel.progress.value.sessionId.takeIf { it > 0 }
            ?: viewModel.lastSessionId()

        if (sessionId > 0) {
            findNavController().navigate(
                R.id.action_scan_to_results,
                bundleOf("sessionId" to sessionId)
            )
        } else {
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
