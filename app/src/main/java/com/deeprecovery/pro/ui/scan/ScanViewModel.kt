package com.deeprecovery.pro.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.data.model.ScanStatus
import com.deeprecovery.pro.engine.scanner.ScanProgress
import com.deeprecovery.pro.engine.scanner.ScanRequest
import com.deeprecovery.pro.work.ScanController
import com.deeprecovery.pro.work.ScanWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DeepRecoveryApp).repository
    private val workManager = WorkManager.getInstance(app)

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private val _finishedSessionId = MutableStateFlow(-1L)
    val finishedSessionId: StateFlow<Long> = _finishedSessionId.asStateFlow()

    private var engineJob: Job? = null

    /** يبدأ الفحص (أو يستأنف جلسة سابقة). */
    fun startScan(request: ScanRequest, resumeSessionId: Long = -1L) {
        val work = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(ScanWorker.buildInput(request, resumeSessionId))
            .addTag(ScanWorker.WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            ScanWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work
        )
        observeWork()
        observeEngine()
    }

    private fun observeEngine() {
        engineJob?.cancel()
        engineJob = viewModelScope.launch {
            // ننتظر حتى يهيّئ العامل المحرك ثم نتابع تدفّق التقدّم
            while (ScanController.current == null) {
                kotlinx.coroutines.delay(150)
            }
            ScanController.current?.progress?.collectLatest { value ->
                _progress.value = value
                if (value.status == ScanStatus.COMPLETED && value.sessionId > 0) {
                    _finishedSessionId.value = value.sessionId
                }
            }
        }
    }

    private fun observeWork() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ScanWorker.WORK_NAME)
                .collectLatest { infos ->
                    val info = infos.firstOrNull() ?: return@collectLatest
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val id = info.outputData.getLong(ScanWorker.KEY_SESSION_ID, -1L)
                            if (id > 0) _finishedSessionId.value = id
                            _progress.value = _progress.value.copy(status = ScanStatus.COMPLETED)
                        }

                        WorkInfo.State.FAILED ->
                            _progress.value = _progress.value.copy(status = ScanStatus.FAILED)

                        WorkInfo.State.CANCELLED ->
                            _progress.value = _progress.value.copy(status = ScanStatus.CANCELLED)

                        else -> Unit
                    }
                }
        }
    }

    fun pause() {
        ScanController.pause()
        _progress.value = _progress.value.copy(status = ScanStatus.PAUSED)
    }

    fun resume() {
        ScanController.resume()
        _progress.value = _progress.value.copy(status = ScanStatus.RUNNING)
    }

    fun cancel() {
        workManager.cancelUniqueWork(ScanWorker.WORK_NAME)
        engineJob?.cancel()
        _progress.value = _progress.value.copy(status = ScanStatus.CANCELLED)
    }

    /** يعيد الاتصال بجلسة فحص قيد التشغيل عند العودة إلى الشاشة. */
    fun reattach() {
        observeWork()
        observeEngine()
    }

    fun lastSessionId(): Long = repository.prefs.lastSessionId
}
