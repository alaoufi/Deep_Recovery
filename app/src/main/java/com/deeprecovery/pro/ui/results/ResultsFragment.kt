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

        folderAdapter = FolderAdapter(
            onOpen = { folder -> viewModel.openFolder(folder.folderPath) },
            onRecoverAll = { folder -> recoverWholeFolder(folder.folderPath) },
            onToggleSelect = { folder -> viewModel.toggleFolderSelection(folder.folderPath) }
        )

        binding.resultsList.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsList.adapter = fileAdapter
    }

    private fun setupFilters() = with(binding) {
        searchInput.doAfterTextChanged { text -> viewModel.search(text?.toString().orEmpty()) }

        chipFolders.setOnClickListener {
            if (chipFolders.isChecked) {
                viewModel.openFolder(null)
            } else {
                viewModel.setView(ResultsView.FILES)
            }
        }

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
    }

    private fun recoverWholeFolder(folderPath: String) {
        RecoverySheet.forFolder(viewModel.sessionId.value, folderPath)
            .show(childFragmentManager, RecoverySheet.TAG)
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.view.collectLatest { view ->
                        binding.resultsList.adapter = when (view) {
                            ResultsView.FILES -> fileAdapter
                            ResultsView.FOLDERS -> folderAdapter
                        }
                        binding.chipFolders.isChecked = view == ResultsView.FOLDERS
                    }
                }

                launch {
                    viewModel.files.collectLatest { files ->
                        fileAdapter.submitList(files)
                        if (viewModel.view.value == ResultsView.FILES) {
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
                        binding.selectionText.text =
                            getString(R.string.selected_count, ids.size)
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

    private fun updateEmptyState(empty: Boolean) {
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        binding.resultsList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
