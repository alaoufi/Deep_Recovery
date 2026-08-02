package com.deeprecovery.pro.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.db.ScanSessionEntity
import com.deeprecovery.pro.data.model.ScanStatus
import com.deeprecovery.pro.databinding.FragmentSessionsBinding
import com.deeprecovery.pro.databinding.ItemSessionBinding
import com.deeprecovery.pro.util.FormatUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** الفحوصات المحفوظة — نتائج كل جلسة تبقى متاحة للرجوع إليها. */
class SessionsFragment : Fragment() {

    private var _binding: FragmentSessionsBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy { DeepRecoveryApp.instance.repository }
    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SessionAdapter(
            onOpen = { session ->
                findNavController().navigate(
                    R.id.action_sessions_to_results,
                    bundleOf("sessionId" to session.id)
                )
            },
            onDelete = { session -> confirmDelete(session) }
        )

        binding.sessionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.sessionsList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeSessions().collectLatest { adapter.submitList(it) }
            }
        }
    }

    private fun confirmDelete(session: ScanSessionEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.session_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deleteSession(session.id)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SessionAdapter(
    private val onOpen: (ScanSessionEntity) -> Unit,
    private val onDelete: (ScanSessionEntity) -> Unit
) : ListAdapter<ScanSessionEntity, SessionAdapter.SessionViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class SessionViewHolder(
        private val binding: ItemSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ScanSessionEntity) = with(binding) {
            val context = root.context
            sessionDate.text = FormatUtils.formatDateTime(item.startedAt)

            val statusText = context.getString(
                when (item.status) {
                    ScanStatus.COMPLETED -> R.string.scan_completed
                    ScanStatus.CANCELLED -> R.string.scan_cancelled
                    ScanStatus.FAILED -> R.string.scan_failed
                    ScanStatus.PAUSED -> R.string.scan_paused_hint
                    else -> R.string.scan_title
                }
            )
            sessionSummary.text =
                context.getString(R.string.session_summary, item.filesFound, statusText)

            root.setOnClickListener { onOpen(item) }
            deleteSessionButton.setOnClickListener { onDelete(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanSessionEntity>() {
            override fun areItemsTheSame(old: ScanSessionEntity, new: ScanSessionEntity) =
                old.id == new.id

            override fun areContentsTheSame(old: ScanSessionEntity, new: ScanSessionEntity) =
                old == new
        }
    }
}
