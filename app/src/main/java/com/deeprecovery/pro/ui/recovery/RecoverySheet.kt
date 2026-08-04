package com.deeprecovery.pro.ui.recovery

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deeprecovery.pro.R
import com.deeprecovery.pro.databinding.SheetRecoveryBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ورقة الاستعادة: اختيار مكان الحفظ وبدء العملية.
 *
 * تعمل بوضعين:
 *  - استعادة ملفات محددة.
 *  - استعادة **مجلد كامل بما فيه** مع إعادة بناء شجرة المجلدات.
 */
class RecoverySheet : BottomSheetDialogFragment() {

    private var _binding: SheetRecoveryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecoveryViewModel by viewModels()

    private val pickDestination = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDestination(uri)
            renderDestination()
        }
    }

    private val sessionId: Long get() = requireArguments().getLong(ARG_SESSION)
    private val folderPath: String? get() = requireArguments().getString(ARG_FOLDER)
    private val fileIds: List<Long>
        get() = requireArguments().getLongArray(ARG_FILE_IDS)?.toList().orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetRecoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.summaryText.text = folderPath?.let {
            getString(R.string.folder_recover_all) + " — " + it
        } ?: getString(R.string.selected_count, fileIds.size)

        binding.preserveSwitch.isChecked = viewModel.preserveStructure
        binding.skipDuplicatesSwitch.isChecked = viewModel.skipDuplicates

        binding.albumInput.setText(viewModel.albumName)
        binding.albumInput.doAfterTextChanged { renderAlbumHelp(it?.toString().orEmpty()) }
        renderAlbumHelp(viewModel.albumName)

        renderDestination()

        binding.chooseDestinationButton.setOnClickListener { pickDestination.launch(null) }

        binding.preserveSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.preserveStructure = checked
        }
        binding.skipDuplicatesSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.skipDuplicates = checked
        }

        binding.startRecoveryButton.setOnClickListener { start() }

        observe()
    }

    private fun renderDestination() {
        val label = viewModel.destinationUri?.lastPathSegment
            ?: getString(R.string.recovery_destination_default)
        binding.destinationText.text = getString(R.string.recovery_destination_current, label)
    }

    /** يُظهر المسار النهائي قبل البدء حتى لا يبحث المستخدم عن ملفاته بعدها. */
    private fun renderAlbumHelp(raw: String) {
        val album = com.deeprecovery.pro.util.AppPrefs.sanitizeAlbum(raw)
        binding.albumHelp.text = getString(R.string.recovery_album_target, album)
    }

    private fun start() {
        // الاسم يُثبَّت لحظة البدء: القراءة عند كل حرف تحفظ أسماء ناقصة
        viewModel.albumName = binding.albumInput.text?.toString().orEmpty()

        isCancelable = false
        binding.startRecoveryButton.isEnabled = false
        binding.recoveryProgress.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE

        val folder = folderPath
        if (folder != null) {
            viewModel.recoverFolder(sessionId, folder)
        } else {
            viewModel.recoverFiles(sessionId, fileIds)
        }
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    if (state.running) {
                        binding.recoveryProgress.max = state.total.coerceAtLeast(1)
                        binding.recoveryProgress.setProgressCompat(state.current, true)
                        binding.progressText.text = getString(
                            R.string.recovery_running,
                            state.current,
                            state.total
                        ) + "\n" + getString(R.string.recovery_current_folder, state.currentFolder)
                    }
                    if (state.finished) {
                        parentFragmentManager.let { fm ->
                            ReportSheet.newInstance(state).show(fm, ReportSheet.TAG)
                        }
                        viewModel.consumeResult()
                        dismissAllowingStateLoss()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RecoverySheet"
        private const val ARG_SESSION = "session"
        private const val ARG_FOLDER = "folder"
        private const val ARG_FILE_IDS = "file_ids"

        fun forFiles(sessionId: Long, ids: List<Long>) = RecoverySheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_SESSION, sessionId)
                putLongArray(ARG_FILE_IDS, ids.toLongArray())
            }
        }

        fun forFolder(sessionId: Long, folderPath: String) = RecoverySheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_SESSION, sessionId)
                putString(ARG_FOLDER, folderPath)
            }
        }
    }
}
