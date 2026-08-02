package com.deeprecovery.pro.data.repository

import android.content.Context
import com.deeprecovery.pro.data.db.AppDatabase
import com.deeprecovery.pro.data.db.FolderSummary
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.db.RecoveryReportEntity
import com.deeprecovery.pro.data.db.ScanSessionEntity
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.util.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** فلاتر شاشة النتائج. */
data class ResultFilter(
    val query: String = "",
    val mediaType: MediaType? = null,
    val folderPath: String? = null,
    val minQuality: RecoveryQuality? = null,
    val hideDuplicates: Boolean = false
)

/** طبقة الوصول الوحيدة للبيانات — كل شيء محلي داخل الجهاز. */
class RecoveryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val sessionDao = db.scanSessionDao()
    private val fileDao = db.recoveredFileDao()
    private val reportDao = db.recoveryReportDao()

    val prefs = AppPrefs(appContext)

    fun observeSessions(): Flow<List<ScanSessionEntity>> = sessionDao.observeAll()

    fun observeSession(id: Long): Flow<ScanSessionEntity?> = sessionDao.observeById(id)

    suspend fun getSession(id: Long): ScanSessionEntity? = sessionDao.getById(id)

    suspend fun getResumableSession(): ScanSessionEntity? = sessionDao.getResumable()

    fun observeFiles(sessionId: Long, filter: ResultFilter): Flow<List<RecoveredFileEntity>> =
        fileDao.observeFiltered(
            sessionId = sessionId,
            query = filter.query,
            mediaType = filter.mediaType,
            folderPath = filter.folderPath,
            minQuality = filter.minQuality,
            minConfidence = filter.minQuality?.minConfidence ?: 0,
            hideDuplicates = filter.hideDuplicates
        )

    fun observeFolders(sessionId: Long, hideDuplicates: Boolean): Flow<List<FolderSummary>> =
        fileDao.observeFolders(sessionId, hideDuplicates)

    suspend fun getFile(id: Long): RecoveredFileEntity? = fileDao.getById(id)

    suspend fun getFolderContents(sessionId: Long, folderPath: String): List<RecoveredFileEntity> =
        fileDao.getFolderTreeContents(sessionId, folderPath)

    fun observeReports(sessionId: Long): Flow<List<RecoveryReportEntity>> =
        reportDao.observeForSession(sessionId)

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        // نحذف الملفات المؤقتة المرتبطة بالجلسة قبل حذف السجلات
        fileDao.stagedPathsForSession(sessionId).forEach { path ->
            path?.let { runCatching { File(it).delete() } }
        }
        fileDao.deleteForSession(sessionId)
        sessionDao.delete(sessionId)
    }

    suspend fun countFiles(sessionId: Long): Int = fileDao.countForSession(sessionId)
}
