package com.deeprecovery.pro.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanLocation
import com.deeprecovery.pro.data.model.ScanMode
import com.deeprecovery.pro.engine.root.RootManager
import com.deeprecovery.pro.util.StorageTarget
import com.deeprecovery.pro.util.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** حالة الشاشة الرئيسية. */
data class MainUiState(
    val mode: ScanMode = ScanMode.NORMAL,
    val depth: ScanDepth = ScanDepth.DEEP,
    val includeImages: Boolean = true,
    val includeVideos: Boolean = true,
    val selectedLocations: Set<ScanLocation> = emptySet(),
    val availableTargets: List<StorageTarget> = emptyList(),
    val rootAvailable: Boolean = false,
    val rootChecked: Boolean = false,
    val hasResumableSession: Boolean = false,
    val resumableSessionId: Long = -1L,
    val lastSessionId: Long = -1L,
    val freeSpaceBytes: Long = 0
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DeepRecoveryApp).repository
    private val prefs = repository.prefs

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val targets = withContext(Dispatchers.IO) {
                StorageUtils.resolveTargets(getApplication())
            }
            val saved = prefs.selectedLocations.mapNotNull { ScanLocation.fromKey(it) }.toSet()
            val resumable = repository.getResumableSession()

            _state.value = _state.value.copy(
                mode = prefs.scanMode,
                depth = prefs.scanDepth,
                includeImages = prefs.includeImages,
                includeVideos = prefs.includeVideos,
                selectedLocations = saved.ifEmpty {
                    targets.filter { it.available }.map { it.location }.toSet()
                },
                availableTargets = targets,
                hasResumableSession = resumable != null,
                resumableSessionId = resumable?.id ?: -1L,
                lastSessionId = prefs.lastSessionId,
                freeSpaceBytes = withContext(Dispatchers.IO) {
                    StorageUtils.freeSpace(StorageUtils.stagingDir(getApplication()))
                }
            )
            checkRoot()
        }
    }

    fun refresh() = load()

    fun checkRoot(force: Boolean = false) {
        viewModelScope.launch {
            val available = RootManager.isRootAvailable(force)
            _state.value = _state.value.copy(rootAvailable = available, rootChecked = true)
            if (!available && _state.value.mode == ScanMode.ROOT) {
                setMode(ScanMode.NORMAL)
            }
        }
    }

    fun setMode(mode: ScanMode) {
        prefs.scanMode = mode
        val locations = if (mode == ScanMode.ROOT) {
            _state.value.selectedLocations + ScanLocation.RAW_BLOCK
        } else {
            _state.value.selectedLocations - ScanLocation.RAW_BLOCK
        }
        _state.value = _state.value.copy(mode = mode, selectedLocations = locations)
        persistLocations(locations)
    }

    fun setDepth(depth: ScanDepth) {
        prefs.scanDepth = depth
        _state.value = _state.value.copy(depth = depth)
    }

    fun setIncludeImages(value: Boolean) {
        prefs.includeImages = value
        _state.value = _state.value.copy(includeImages = value)
    }

    fun setIncludeVideos(value: Boolean) {
        prefs.includeVideos = value
        _state.value = _state.value.copy(includeVideos = value)
    }

    fun toggleLocation(location: ScanLocation, selected: Boolean) {
        val updated = if (selected) {
            _state.value.selectedLocations + location
        } else {
            _state.value.selectedLocations - location
        }
        _state.value = _state.value.copy(selectedLocations = updated)
        persistLocations(updated)
    }

    private fun persistLocations(locations: Set<ScanLocation>) {
        prefs.selectedLocations = locations.map { it.key }.toSet()
    }

    /** التحقق من صلاحية الإعدادات قبل بدء الفحص. */
    fun validate(): Int? = when {
        !_state.value.includeImages && !_state.value.includeVideos -> {
            com.deeprecovery.pro.R.string.error_no_media_type
        }
        _state.value.selectedLocations.isEmpty() -> {
            com.deeprecovery.pro.R.string.error_no_location
        }
        else -> null
    }
}
