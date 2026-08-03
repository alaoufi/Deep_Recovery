package com.deeprecovery.pro.ui.recovery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TableRow
import android.widget.TextView
import com.deeprecovery.pro.R
import com.deeprecovery.pro.databinding.SheetReportBinding
import com.deeprecovery.pro.util.FormatUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** تقرير نجاح الاستعادة بعد انتهاء العملية. */
class ReportSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReportBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        val rate = args.getInt(ARG_RATE)
        binding.rateIndicator.max = 100
        binding.rateIndicator.setProgressCompat(rate, true)
        binding.rateText.text = getString(R.string.scan_percent, rate)

        addRow(R.string.report_succeeded, args.getInt(ARG_SUCCEEDED).toString())
        addRow(R.string.report_failed, args.getInt(ARG_FAILED).toString())
        addRow(R.string.report_skipped, args.getInt(ARG_SKIPPED).toString())
        addRow(R.string.report_folders, args.getInt(ARG_FOLDERS).toString())
        addRow(
            R.string.report_bytes,
            FormatUtils.formatSize(requireContext(), args.getLong(ARG_BYTES))
        )
        addRow(R.string.report_destination, args.getString(ARG_DESTINATION).orEmpty())

        // بلا هذا يبقى الفشل صامتاً ولا يعرف المستخدم لماذا لم يُستعَد شيء
        val failures = args.getStringArray(ARG_FAILURES).orEmpty()
        if (failures.isNotEmpty()) {
            addRow(R.string.report_failure_reasons, "")
            failures.forEach { reason ->
                val row = TableRow(requireContext())
                row.addView(TextView(requireContext()).apply {
                    text = reason
                    setPadding(0, 4, 0, 4)
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                    )
                })
                binding.reportTable.addView(row)
            }
        }

        binding.reportDoneButton.setOnClickListener { dismiss() }
    }

    private fun addRow(labelRes: Int, value: String) {
        val row = TableRow(requireContext())

        val label = TextView(requireContext()).apply {
            text = getString(labelRes)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, 8, 0, 8)
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(0, 8, 0, 8)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }

        row.addView(label)
        row.addView(valueView)
        binding.reportTable.addView(row)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ReportSheet"

        private const val ARG_RATE = "rate"
        private const val ARG_SUCCEEDED = "succeeded"
        private const val ARG_FAILED = "failed"
        private const val ARG_SKIPPED = "skipped"
        private const val ARG_FOLDERS = "folders"
        private const val ARG_BYTES = "bytes"
        private const val ARG_DESTINATION = "destination"
        private const val ARG_FAILURES = "failures"

        fun newInstance(state: RecoveryUiState) = ReportSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_RATE, state.successRate)
                putInt(ARG_SUCCEEDED, state.succeeded)
                putInt(ARG_FAILED, state.failed)
                putInt(ARG_SKIPPED, state.skipped)
                putInt(ARG_FOLDERS, state.foldersCreated)
                putLong(ARG_BYTES, state.bytesWritten)
                putString(ARG_DESTINATION, state.destination)
                putStringArray(ARG_FAILURES, state.failures.toTypedArray())
            }
        }
    }
}
