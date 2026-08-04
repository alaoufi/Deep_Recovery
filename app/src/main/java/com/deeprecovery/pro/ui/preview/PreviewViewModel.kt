package com.deeprecovery.pro.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deeprecovery.pro.DeepRecoveryApp
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.repository.ResultFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PreviewViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as DeepRecoveryApp).repository

    suspend fun load(fileId: Long): RecoveredFileEntity? = repository.getFile(fileId)

    /**
     * كل ملفات الجلسة بنفس ترتيب شاشة النتائج، ليتمكن المستخدم من
     * التنقل بينها بالتمرير بدل الاقتصار على ملف واحد.
     */
    suspend fun loadSiblings(sessionId: Long): List<RecoveredFileEntity> =
        repository.observeFiles(sessionId, ResultFilter()).first()

    /**
     * حذف نهائي للمقطع المعروض — لا رجعة فيه.
     *
     * يُستدعى فقط بعد تأكيد صريح من المستخدم.
     */
    fun deleteForever(
        id: Long,
        onDone: (com.deeprecovery.pro.engine.DeleteReport) -> Unit
    ) {
        viewModelScope.launch {
            val engine = com.deeprecovery.pro.engine.SecureDeleteEngine(getApplication())
            onDone(engine.deleteForever(listOf(id)))
        }
    }

    /** يزيل السجل بعد أن ينفّذ النظام الحذف بموافقة المستخدم. */
    fun forgetRecord(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            com.deeprecovery.pro.engine.SecureDeleteEngine(getApplication())
                .forgetRecords(listOf(id))
            onDone()
        }
    }
}
