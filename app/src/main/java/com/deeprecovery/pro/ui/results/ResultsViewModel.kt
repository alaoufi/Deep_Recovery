package com.deeprecovery.pro.ui.results

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.data.db.FolderSummary
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.data.repository.ResultFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** طريقة عرض النتائج: قائمة ملفات أو مجلدات. */
enum class ResultsView { FILES, FOLDERS }

@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DeepRecoveryApp).repository

    private val _sessionId = MutableStateFlow(-1L)
    val sessionId: StateFlow<Long> = _sessionId.asStateFlow()

    private val _filter = MutableStateFlow(ResultFilter())
    val filter: StateFlow<ResultFilter> = _filter.asStateFlow()

    private val _view = MutableStateFlow(ResultsView.FILES)
    val view: StateFlow<ResultsView> = _view.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** المجلدات المختارة بالكامل — تُستعاد بكل محتوياتها. */
    private val _selectedFolders = MutableStateFlow<Set<String>>(emptySet())
    val selectedFolders: StateFlow<Set<String>> = _selectedFolders.asStateFlow()

    val files: StateFlow<List<RecoveredFileEntity>> =
        combineSessionAndFilter().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<FolderSummary>> =
        combine(_sessionId, _filter) { id, filter -> id to filter.hideDuplicates }
            .flatMapLatest { (id, hideDuplicates) ->
                if (id <= 0) flowOf(emptyList()) else repository.observeFolders(id, hideDuplicates)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun combineSessionAndFilter() =
        combine(_sessionId, _filter) { id, filter -> id to filter }
            .flatMapLatest { (id, filter) ->
                if (id <= 0) flowOf(emptyList()) else repository.observeFiles(id, filter)
            }

    fun setSession(id: Long) {
        _sessionId.value = id
    }

    fun setView(view: ResultsView) {
        _view.value = view
    }

    fun search(query: String) {
        _filter.value = _filter.value.copy(query = query.trim())
    }

    fun filterMediaType(type: MediaType?) {
        _filter.value = _filter.value.copy(mediaType = type)
    }

    fun filterQuality(quality: RecoveryQuality?) {
        _filter.value = _filter.value.copy(minQuality = quality)
    }

    fun setHideDuplicates(hide: Boolean) {
        _filter.value = _filter.value.copy(hideDuplicates = hide)
    }

    /** يفتح مجلداً محدداً لعرض ملفاته فقط. */
    fun openFolder(folderPath: String?) {
        _filter.value = _filter.value.copy(folderPath = folderPath)
        _view.value = if (folderPath == null) ResultsView.FOLDERS else ResultsView.FILES
    }

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
    }

    fun selectAllVisible() {
        _selectedIds.value = files.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        _selectedFolders.value = emptySet()
    }

    /**
     * يختار/يلغي اختيار **مجلد كامل بما فيه** — كل الملفات داخله
     * وداخل مجلداته الفرعية.
     */
    fun toggleFolderSelection(folderPath: String) {
        viewModelScope.launch {
            val current = _selectedFolders.value
            val contents = repository.getFolderContents(_sessionId.value, folderPath)
            val ids = contents.map { it.id }.toSet()

            if (folderPath in current) {
                _selectedFolders.value = current - folderPath
                _selectedIds.value = _selectedIds.value - ids
            } else {
                _selectedFolders.value = current + folderPath
                _selectedIds.value = _selectedIds.value + ids
            }
        }
    }

    fun selectAllFolders() {
        viewModelScope.launch {
            val all = folders.value.map { it.folderPath }
            _selectedFolders.value = all.toSet()
            val ids = all.flatMap { repository.getFolderContents(_sessionId.value, it) }
                .map { it.id }
                .toSet()
            _selectedIds.value = ids
        }
    }

    /**
     * حذف نهائي للملفات المحددة.
     *
     * عملية لا رجعة فيها، لذلك تُستدعى فقط بعد تأكيد صريح من المستخدم.
     */
    fun deleteForever(
        ids: List<Long>,
        onDone: (com.deeprecovery.pro.engine.DeleteReport) -> Unit
    ) {
        viewModelScope.launch {
            val engine = com.deeprecovery.pro.engine.SecureDeleteEngine(getApplication())
            val report = engine.deleteForever(ids)
            clearSelection()
            onDone(report)
        }
    }

    fun forgetRecords(ids: List<Long>) {
        viewModelScope.launch {
            com.deeprecovery.pro.engine.SecureDeleteEngine(getApplication()).forgetRecords(ids)
        }
    }

    fun deleteSession(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteSession(id)
            onDone()
        }
    }

    val selectionCount: Int get() = _selectedIds.value.size
}
