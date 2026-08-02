package com.deeprecovery.pro.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanLocation
import com.deeprecovery.pro.data.model.ScanMode
import com.deeprecovery.pro.databinding.FragmentMainBinding
import com.deeprecovery.pro.databinding.ItemLocationBinding
import com.deeprecovery.pro.ui.common.InfoDialogs
import com.deeprecovery.pro.util.CrashReporter
import com.deeprecovery.pro.util.FormatUtils
import com.deeprecovery.pro.util.StorageUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * الشاشة الرئيسية: اختيار وضع الفحص ونوعه ومكانه، ثم بدء الفحص العميق.
 */
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private var bindingLocations = false

    /**
     * اختيار مجلد بعينه عبر Storage Access Framework.
     *
     * يحوَّل عنوان الشجرة إلى مسار حقيقي ليمرّ عليه الماسح مباشرة، فيصبح
     * الفحص محصوراً فيما اختاره المستخدم وسريعاً.
     */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val documentId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull()
        val path = documentId?.let { StorageUtils.pathFromTreeUri(it) }

        if (path == null || !java.io.File(path).isDirectory) {
            Snackbar.make(binding.root, R.string.pick_folder_invalid, Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        prefs.addCustomFolder(path)
        renderCustomFolders()
    }

    private val prefs by lazy { com.deeprecovery.pro.DeepRecoveryApp.instance.repository.prefs }

    private val mediaPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            startScan()
        } else {
            Snackbar.make(binding.root, R.string.perm_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeState()
        maybeShowCrashReport()
        maybeShowDisclaimer()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    /** إذا انهار التطبيق في التشغيل السابق نعرض التفاصيل مرة واحدة. */
    private fun maybeShowCrashReport() {
        val context = requireContext()
        if (!CrashReporter.hasPendingReport(context)) return
        CrashReporter.consumeReport(context)
        InfoDialogs.showCrashReport(context)
    }

    private fun maybeShowDisclaimer() {
        val prefs = viewModel.let { com.deeprecovery.pro.DeepRecoveryApp.instance.repository.prefs }
        if (!prefs.disclaimerAccepted) {
            InfoDialogs.showDisclaimer(requireContext()) {
                prefs.disclaimerAccepted = true
            }
        }
    }

    private fun setupListeners() = with(binding) {
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.modeRoot) ScanMode.ROOT else ScanMode.NORMAL
            if (mode == ScanMode.ROOT && !viewModel.state.value.rootAvailable) {
                Snackbar.make(root, R.string.error_root_required, Snackbar.LENGTH_LONG).show()
                modeNormal.isChecked = true
                return@setOnCheckedChangeListener
            }
            viewModel.setMode(mode)
        }

        checkRootButton.setOnClickListener { viewModel.checkRoot(force = true) }

        depthGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val depth = when (checkedId) {
                R.id.depthQuick -> ScanDepth.QUICK
                R.id.depthFull -> ScanDepth.FULL
                else -> ScanDepth.DEEP
            }
            viewModel.setDepth(depth)
        }

        checkImages.setOnCheckedChangeListener { _, checked -> viewModel.setIncludeImages(checked) }
        checkVideos.setOnCheckedChangeListener { _, checked -> viewModel.setIncludeVideos(checked) }

        startScanButton.setOnClickListener { requestPermissionsThenScan() }

        grantAllFilesButton.setOnClickListener { openAllFilesSettings() }

        pickFolderButton.setOnClickListener { pickFolder.launch(null) }

        ignoreScreenshotsCheck.isChecked = prefs.ignoreScreenshots
        ignoreScreenshotsCheck.setOnCheckedChangeListener { _, checked ->
            prefs.ignoreScreenshots = checked
        }

        resumeScanButton.setOnClickListener {
            val id = viewModel.state.value.resumableSessionId
            navigateToScan(id)
        }

        lastResultsButton.setOnClickListener {
            val id = viewModel.state.value.lastSessionId
            if (id > 0) {
                findNavController().navigate(
                    R.id.action_main_to_results,
                    bundleOf("sessionId" to id)
                )
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state -> render(state) }
            }
        }
    }

    private fun render(state: MainUiState) = with(binding) {
        when (state.mode) {
            ScanMode.NORMAL -> if (!modeNormal.isChecked) modeNormal.isChecked = true
            ScanMode.ROOT -> if (!modeRoot.isChecked) modeRoot.isChecked = true
        }

        val depthButton = when (state.depth) {
            ScanDepth.QUICK -> R.id.depthQuick
            ScanDepth.DEEP -> R.id.depthDeep
            ScanDepth.FULL -> R.id.depthFull
        }
        if (depthGroup.checkedButtonId != depthButton) depthGroup.check(depthButton)
        depthDescription.setText(state.depth.descriptionRes)

        if (checkImages.isChecked != state.includeImages) checkImages.isChecked = state.includeImages
        if (checkVideos.isChecked != state.includeVideos) checkVideos.isChecked = state.includeVideos

        rootStatusText.setText(
            if (state.rootAvailable) R.string.root_available else R.string.root_unavailable
        )
        modeRoot.isEnabled = state.rootAvailable

        freeSpaceText.text = getString(
            R.string.free_space,
            FormatUtils.formatSize(requireContext(), state.freeSpaceBytes)
        )

        // بدون هذا الإذن لا يستطيع النظام سرد معظم المجلدات فتكون
        // النتائج شبه معدومة — نُظهره بوضوح بدل تركه تحذيراً عابراً
        allFilesCard.visibility =
            if (needsAllFilesAccess(state)) View.VISIBLE else View.GONE

        resumeScanButton.visibility =
            if (state.hasResumableSession) View.VISIBLE else View.GONE
        lastResultsButton.visibility =
            if (state.lastSessionId > 0) View.VISIBLE else View.GONE

        renderLocations(state)
        renderCustomFolders()
    }

    /** يعرض المجلدات المختارة كرقائق قابلة للإزالة. */
    private fun renderCustomFolders() {
        val group = binding.customFoldersGroup
        val folders = prefs.customFolders.toList().sorted()
        group.removeAllViews()

        folders.forEach { path ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = path.substringAfterLast('/').ifEmpty { path }
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    prefs.removeCustomFolder(path)
                    renderCustomFolders()
                }
            }
            group.addView(chip)
        }

        binding.customFoldersHint.visibility =
            if (folders.isEmpty()) View.GONE else View.VISIBLE
        // عند تحديد مجلد بعينه لا معنى لخيارات الأماكن الجاهزة
        binding.locationsContainer.alpha = if (folders.isEmpty()) 1f else 0.4f
    }

    private fun renderLocations(state: MainUiState) {
        val container = binding.locationsContainer
        if (container.childCount != state.availableTargets.size) {
            container.removeAllViews()
            state.availableTargets.forEach { target ->
                val row = ItemLocationBinding.inflate(layoutInflater, container, false)
                row.locationCheck.setText(target.location.labelRes)
                row.locationCheck.isEnabled = target.available
                row.locationStatus.text =
                    if (target.available) target.label else getString(R.string.loc_unavailable)
                row.locationCheck.setOnCheckedChangeListener { _, checked ->
                    if (!bindingLocations) viewModel.toggleLocation(target.location, checked)
                }
                row.root.tag = target.location
                container.addView(row.root)
            }
        }

        bindingLocations = true
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val location = child.tag as? ScanLocation ?: continue
            val check = ItemLocationBinding.bind(child).locationCheck
            val shouldCheck = location in state.selectedLocations
            if (check.isChecked != shouldCheck) check.isChecked = shouldCheck
        }
        bindingLocations = false
    }

    private fun needsAllFilesAccess(state: MainUiState): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !state.hasAllFilesAccess

    // ------------------------------------------------------------ الصلاحيات

    private fun requestPermissionsThenScan() {
        val error = viewModel.validate()
        if (error != null) {
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            return
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needsAllFiles = viewModel.state.value.depth != ScanDepth.QUICK &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !StorageUtils.hasAllFilesAccess()

        if (needsAllFiles) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.all_files_title)
                .setMessage(R.string.all_files_warning_scan)
                .setIcon(R.drawable.ic_info)
                .setPositiveButton(R.string.all_files_grant) { _, _ -> openAllFilesSettings() }
                .setNegativeButton(R.string.all_files_continue_anyway) { _, _ ->
                    mediaPermissions.launch(permissions)
                }
                .show()
            return
        }

        mediaPermissions.launch(permissions)
    }

    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun startScan() = navigateToScan(-1L)

    private fun navigateToScan(resumeSessionId: Long) {
        findNavController().navigate(
            R.id.action_main_to_scan,
            bundleOf("resumeSessionId" to resumeSessionId)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
