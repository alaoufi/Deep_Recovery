package com.deeprecovery.pro.ui.results

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.databinding.FragmentResultsBinding
import com.deeprecovery.pro.ui.adapter.FolderAdapter
import com.deeprecovery.pro.ui.adapter.RecoveredFileAdapter
import com.deeprecovery.pro.ui.recovery.RecoverySheet
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * شاشة النتائج: عرض الملفات أو المجلدات، بحث، فلترة، وتحديد للاستعادة.
 *
 * في عرض المجلدات يمكن استعادة **المجلد كاملاً بما فيه** مباشرة.
 */
class ResultsFragment : Fragment() {

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ResultsViewModel by viewModels()

    private lateinit var fileAdapter: RecoveredFileAdapter
    private lateinit var gridAdapter: com.deeprecovery.pro.ui.adapter.MediaGridAdapter

    /** نتيجة نافذة الحذف التي يعرضها النظام. */
    private val systemDelete = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.forgetRecords(pendingConsentIds)
        }
        pendingConsentIds = emptyList()
    }

    private var pendingConsentIds: List<Long> = emptyList()
    private lateinit var folderAdapter: FolderAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sessionId = arguments?.getLong("sessionId", -1L) ?: -1L
        viewModel.setSession(sessionId)

        setupAdapters()
        setupFilters()
        setupActions()
        observe()
    }

    private fun setupAdapters() {
        fileAdapter = RecoveredFileAdapter(
            onClick = { file ->
                findNavController().navigate(
                    R.id.action_results_to_preview,
                    bundleOf("fileId" to file.id)
                )
            },
            onToggleSelect = { file -> viewModel.toggleSelection(file.id) }
        )

        gridAdapter = com.deeprecovery.pro.ui.adapter.MediaGridAdapter(
            onClick = { file ->
                findNavController().navigate(
                    R.id.action_results_to_preview,
                    bundleOf("fileId" to file.id)
                )
            },
            onToggleSelect = { file -> viewModel.toggleSelection(file.id) }
        )

        folderAdapter = FolderAdapter(
            onOpen = { folder -> viewModel.openFolder(folder.folderPath) },
            onRecoverAll = { folder -> recoverWholeFolder(folder.folderPath) },
            onToggleSelect = { folder -> viewModel.toggleFolderSelection(folder.folderPath) }
        )

        applyView(ResultsView.GRID)
    }

    private fun setupFilters() = with(binding) {
        searchInput.doAfterTextChanged { text -> viewModel.search(text?.toString().orEmpty()) }

        chipGrid.setOnClickListener { viewModel.setView(ResultsView.GRID) }
        chipList.setOnClickListener { viewModel.setView(ResultsView.FILES) }
        chipFolders.setOnClickListener { viewModel.openFolder(null) }

        chipAll.setOnClickListener { viewModel.filterMediaType(null) }
        chipImages.setOnClickListener { viewModel.filterMediaType(MediaType.IMAGE) }
        chipVideos.setOnClickListener { viewModel.filterMediaType(MediaType.VIDEO) }

        chipExcellent.setOnCheckedChangeListener { _, checked ->
            viewModel.filterQuality(if (checked) RecoveryQuality.EXCELLENT else null)
        }
        chipNoDuplicates.setOnCheckedChangeListener { _, checked ->
            viewModel.setHideDuplicates(checked)
        }
    }

    private fun setupActions() = with(binding) {
        selectAllButton.setOnClickListener {
            if (viewModel.view.value == ResultsView.FOLDERS) {
                viewModel.selectAllFolders()
            } else {
                viewModel.selectAllVisible()
            }
        }

        recoverButton.setOnClickListener {
            val ids = viewModel.selectedIds.value.toList()
            if (ids.isEmpty()) {
                Snackbar.make(root, R.string.recovery_nothing_selected, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            RecoverySheet.forFiles(viewModel.sessionId.value, ids)
                .show(childFragmentManager, RecoverySheet.TAG)
        }

        breadcrumbText.setOnClickListener { viewModel.openFolder(null) }

        deleteForeverButton.setOnClickListener {
            val ids = viewModel.selectedIds.value.toList()
            if (ids.isEmpty()) {
                Snackbar.make(root, R.string.recovery_nothing_selected, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            confirmDeleteForever(ids)
        }
    }

    /** الحذف النهائي لا رجعة فيه، فلا يبدأ إلا بتأكيد صريح. */
    private fun confirmDeleteForever(ids: List<Long>) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_forever_title)
            .setMessage(getString(R.string.delete_forever_body, ids.size))
            .setIcon(R.drawable.ic_info)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.delete_forever_confirm) { _, _ -> runDeleteForever(ids) }
            .show()
    }

    private fun runDeleteForever(ids: List<Long>) {
        viewModel.deleteForever(ids) { report ->
            val message = when {
                report.needsConsent.isNotEmpty() ->
                    getString(R.string.delete_forever_consent, report.needsConsent.size)

                report.failed > 0 ->
                    getString(R.string.delete_forever_partial, report.wiped, report.failed)

                else -> getString(R.string.delete_forever_done, report.wiped)
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            if (report.needsConsent.isNotEmpty()) {
                pendingConsentIds = ids
                requestSystemDelete(report.needsConsent)
            }
        }
    }

    /**
     * ملفات لا يملكها التطبيق تحتاج موافقة المستخدم عبر نافذة النظام
     * على أندرويد 11+.
     */
    private fun requestSystemDelete(uris: List<android.net.Uri>) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        runCatching {
            val request = android.provider.MediaStore.createDeleteRequest(
                requireContext().contentResolver,
                uris
            )
            systemDelete.launch(
                androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build()
            )
        }
    }

    private fun recoverWholeFolder(folderPath: String) {
        RecoverySheet.forFolder(viewModel.sessionId.value, folderPath)
            .show(childFragmentManager, RecoverySheet.TAG)
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch { viewModel.view.collectLatest { applyView(it) } }

                launch {
                    viewModel.files.collectLatest { files ->
                        fileAdapter.submitList(files)
                        gridAdapter.submitList(files)
                        if (viewModel.view.value != ResultsView.FOLDERS) {
                            updateEmptyState(files.isEmpty())
                        }
                    }
                }

                launch {
                    viewModel.folders.collectLatest { folders ->
                        folderAdapter.submitList(folders)
                        if (viewModel.view.value == ResultsView.FOLDERS) {
                            updateEmptyState(folders.isEmpty())
                        }
                    }
                }

                launch {
                    viewModel.selectedIds.collectLatest { ids ->
                        fileAdapter.submitSelection(ids)
                        gridAdapter.submitSelection(ids)
                        binding.selectionText.text =
                            getString(R.string.selected_count, ids.size)
                        // الشريط كان يغطي النتائج دائماً؛ لا داعي له بلا تحديد
                        binding.actionBarCard.visibility =
                            if (ids.isEmpty()) View.GONE else View.VISIBLE
                    }
                }

                launch {
                    viewModel.selectedFolders.collectLatest { folders ->
                        folderAdapter.submitSelection(folders)
                    }
                }

                launch {
                    viewModel.filter.collectLatest { filter ->
                        val folder = filter.folderPath
                        binding.breadcrumbText.visibility =
                            if (folder == null) View.GONE else View.VISIBLE
                        binding.breadcrumbText.text = folder
                    }
                }
            }
        }
    }

    /** الشبكة ثلاثة أعمدة؛ القائمة والمجلدات عمود واحد. */
    private fun applyView(view: ResultsView) = with(binding) {
        when (view) {
            ResultsView.GRID -> {
                resultsList.layoutManager =
                    androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
                resultsList.adapter = gridAdapter
            }

            ResultsView.FILES -> {
                resultsList.layoutManager = LinearLayoutManager(requireContext())
                resultsList.adapter = fileAdapter
            }

            ResultsView.FOLDERS -> {
                resultsList.layoutManager = LinearLayoutManager(requireContext())
                resultsList.adapter = folderAdapter
            }
        }
        chipGrid.isChecked = view == ResultsView.GRID
        chipList.isChecked = view == ResultsView.FILES
        chipFolders.isChecked = view == ResultsView.FOLDERS
    }

    private fun updateEmptyState(empty: Boolean) {
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        binding.resultsList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
