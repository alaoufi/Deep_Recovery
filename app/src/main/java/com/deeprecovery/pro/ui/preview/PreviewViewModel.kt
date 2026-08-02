package com.deeprecovery.pro.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.data.db.RecoveredFileEntity

class PreviewViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DeepRecoveryApp).repository

    suspend fun load(fileId: Long): RecoveredFileEntity? = repository.getFile(fileId)
}
