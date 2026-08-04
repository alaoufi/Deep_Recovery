package com.deeprecovery.pro.ui.recovery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.work.RecoveryWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** حالة تقرير الاستعادة المعروض للمستخدم. */
data class RecoveryUiState(
    val running: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val currentFolder: String = "",
    val finished: Boolean = false,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val foldersCreated: Int = 0,
    val bytesWritten: Long = 0,
    val successRate: Int = 0,
    val destination: String = "",
    /** أسباب فشل أول الملفات — تُعرض في التقرير. */
    val failures: List<String> = emptyList(),
    /** عناصر سلة المهملات التي تحتاج أمر إلغاء حذف من النظام. */
    val needsUntrash: List<String> = emptyList()
)

class RecoveryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DeepRecoveryApp).repository
    private val workManager = WorkManager.getInstance(app)
    private val prefs = repository.prefs

    private val _state = MutableStateFlow(RecoveryUiState())
    val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    var destinationUri: Uri? = prefs.recoveryTreeUri?.let(Uri::parse)
        private set

    var preserveStructure: Boolean = prefs.preserveFolderStructure
        set(value) {
            field = value
            prefs.preserveFolderStructure = value
        }

    var skipDuplicates: Boolean = prefs.skipDuplicates
        set(value) {
            field = value
            prefs.skipDuplicates = value
        }

    /** اسم الألبوم الذي تُحفظ فيه الملفات المستعادة. */
    var albumName: String = prefs.recoveryAlbumName
        set(value) {
            val clean = com.deeprecovery.pro.util.AppPrefs.sanitizeAlbum(value)
            field = clean
            prefs.recoveryAlbumName = clean
        }

    fun setDestination(uri: Uri) {
        destinationUri = uri
        prefs.recoveryTreeUri = uri.toString()
    }

    /** يستعيد ملفات محددة. */
    fun recoverFiles(sessionId: Long, fileIds: List<Long>) {
        enqueue(
            RecoveryWorker.inputForFiles(
                sessionId,
                fileIds,
                destinationUri,
                preserveStructure,
                skipDuplicates,
                albumName
            )
        )
    }

    /** يستعيد **مجلداً كاملاً بما فيه**، بما في ذلك مجلداته الفرعية. */
    fun recoverFolder(sessionId: Long, folderPath: String) {
        enqueue(
            RecoveryWorker.inputForFolder(
                sessionId,
                folderPath,
                destinationUri,
                preserveStructure,
                skipDuplicates,
                albumName
            )
        )
    }

    private fun enqueue(data: androidx.work.Data) {
        _state.value = RecoveryUiState(running = true)
        val work = OneTimeWorkRequestBuilder<RecoveryWorker>()
            .setInputData(data)
            .addTag(RecoveryWorker.WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            RecoveryWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work
        )
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(RecoveryWorker.WORK_NAME)
                .collectLatest { infos ->
                    val info = infos.firstOrNull() ?: return@collectLatest
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val p = info.progress
                            _state.value = _state.value.copy(
                                running = true,
                                current = p.getInt(RecoveryWorker.KEY_PROGRESS_CURRENT, 0),
                                total = p.getInt(RecoveryWorker.KEY_PROGRESS_TOTAL, 0),
                                currentName = p.getString(RecoveryWorker.KEY_PROGRESS_NAME) ?: "",
                                currentFolder = p.getString(RecoveryWorker.KEY_PROGRESS_FOLDER) ?: ""
                            )
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            val out = info.outputData
                            _state.value = RecoveryUiState(
                                running = false,
                                finished = true,
                                succeeded = out.getInt(RecoveryWorker.KEY_RESULT_SUCCESS, 0),
                                failed = out.getInt(RecoveryWorker.KEY_RESULT_FAILED, 0),
                                skipped = out.getInt(RecoveryWorker.KEY_RESULT_SKIPPED, 0),
                                foldersCreated = out.getInt(RecoveryWorker.KEY_RESULT_FOLDERS, 0),
                                bytesWritten = out.getLong(RecoveryWorker.KEY_RESULT_BYTES, 0),
                                successRate = out.getInt(RecoveryWorker.KEY_RESULT_RATE, 0),
                                destination = out.getString(RecoveryWorker.KEY_RESULT_DESTINATION) ?: "",
                                failures = out.getStringArray(RecoveryWorker.KEY_RESULT_FAILURES)
                                    ?.toList()
                                    .orEmpty(),
                                needsUntrash = out.getStringArray(RecoveryWorker.KEY_RESULT_UNTRASH)
                                    ?.toList()
                                    .orEmpty()
                            )
                        }

                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                            _state.value = _state.value.copy(running = false, finished = true)

                        else -> Unit
                    }
                }
        }
    }

    fun consumeResult() {
        _state.value = RecoveryUiState()
    }
}
