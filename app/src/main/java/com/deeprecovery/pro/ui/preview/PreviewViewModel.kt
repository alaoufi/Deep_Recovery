package com.deeprecovery.pro.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.repository.ResultFilter
import kotlinx.coroutines.flow.first

class PreviewViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DeepRecoveryApp).repository

    suspend fun load(fileId: Long): RecoveredFileEntity? = repository.getFile(fileId)

    /**
     * كل ملفات الجلسة بنفس ترتيب شاشة النتائج، ليتمكن المستخدم من
     * التنقل بينها بالتمرير بدل الاقتصار على ملف واحد.
     */
    suspend fun loadSiblings(sessionId: Long): List<RecoveredFileEntity> =
        repository.observeFiles(sessionId, ResultFilter()).first()
}
