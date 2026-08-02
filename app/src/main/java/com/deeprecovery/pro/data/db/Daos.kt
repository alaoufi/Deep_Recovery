package com.deeprecovery.pro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.data.model.ScanStatus
import kotlinx.coroutines.flow.Flow

/** ملخّص مجلد لعرض النتائج مجمّعة ولاستعادة المجلد كاملاً. */
data class FolderSummary(
    val folderPath: String,
    val folderLabel: String,
    val fileCount: Int,
    val imageCount: Int,
    val videoCount: Int,
    val totalBytes: Long,
    val excellentCount: Int
)

@Dao
interface ScanSessionDao {

    @Insert
    suspend fun insert(session: ScanSessionEntity): Long

    @Update
    suspend fun update(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getById(id: Long): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    fun observeById(id: Long): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM scan_sessions WHERE status IN ('RUNNING','PAUSED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getResumable(): ScanSessionEntity?

    @Query("UPDATE scan_sessions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ScanStatus)

    @Query(
        """
        UPDATE scan_sessions
        SET processedBytes = :processed,
            totalBytes = :total,
            filesFound = :files,
            imagesFound = :images,
            videosFound = :videos
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: Long,
        processed: Long,
        total: Long,
        files: Int,
        images: Int,
        videos: Int
    )

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScanTargetDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(target: ScanTargetEntity): Long

    @Query("SELECT * FROM scan_targets WHERE sessionId = :sessionId ORDER BY id")
    suspend fun getForSession(sessionId: Long): List<ScanTargetEntity>

    @Query("SELECT * FROM scan_targets WHERE sessionId = :sessionId AND sourceKey = :key LIMIT 1")
    suspend fun find(sessionId: Long, key: String): ScanTargetEntity?

    @Query("UPDATE scan_targets SET processedBytes = :processed WHERE id = :id")
    suspend fun updateProgress(id: Long, processed: Long)

    @Query("UPDATE scan_targets SET completed = 1, processedBytes = totalBytes WHERE id = :id")
    suspend fun markCompleted(id: Long)
}

@Dao
interface RecoveredFileDao {

    @Insert
    suspend fun insert(file: RecoveredFileEntity): Long

    @Insert
    suspend fun insertAll(files: List<RecoveredFileEntity>): List<Long>

    @Update
    suspend fun update(file: RecoveredFileEntity)

    @Query("SELECT * FROM recovered_files WHERE id = :id")
    suspend fun getById(id: Long): RecoveredFileEntity?

    @Query("SELECT * FROM recovered_files WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<RecoveredFileEntity>

    @Query("SELECT * FROM recovered_files WHERE sessionId = :sessionId ORDER BY id DESC")
    fun observeBySession(sessionId: Long): Flow<List<RecoveredFileEntity>>

    /** استعلام النتائج مع بحث/فلترة على مستوى قاعدة البيانات. */
    @Query(
        """
        SELECT * FROM recovered_files
        WHERE sessionId = :sessionId
          AND (:query = '' OR displayName LIKE '%' || :query || '%'
                           OR folderLabel LIKE '%' || :query || '%')
          AND (:mediaType IS NULL OR mediaType = :mediaType)
          AND (:folderPath IS NULL OR folderPath = :folderPath)
          AND (:minQuality IS NULL OR confidence >= :minConfidence)
          AND (:hideDuplicates = 0 OR isDuplicate = 0)
        ORDER BY CASE WHEN :oldestFirst = 1 THEN COALESCE(NULLIF(createdAt, 0), discoveredAt) END ASC,
                 CASE WHEN :oldestFirst = 0 THEN COALESCE(NULLIF(createdAt, 0), discoveredAt) END DESC,
                 id ASC
        """
    )
    fun observeFiltered(
        sessionId: Long,
        query: String,
        mediaType: MediaType?,
        folderPath: String?,
        minQuality: RecoveryQuality?,
        minConfidence: Int,
        hideDuplicates: Boolean,
        oldestFirst: Boolean
    ): Flow<List<RecoveredFileEntity>>

    /** كل ملفات مجلد معيّن — أساس استعادة المجلد كاملاً بما فيه. */
    @Query("SELECT * FROM recovered_files WHERE sessionId = :sessionId AND folderPath = :folderPath")
    suspend fun getFolderContents(sessionId: Long, folderPath: String): List<RecoveredFileEntity>

    @Query(
        """
        SELECT * FROM recovered_files
        WHERE sessionId = :sessionId AND (folderPath = :folderPath OR folderPath LIKE :folderPath || '/%')
        """
    )
    suspend fun getFolderTreeContents(
        sessionId: Long,
        folderPath: String
    ): List<RecoveredFileEntity>

    @Query(
        """
        SELECT folderPath,
               folderLabel,
               COUNT(*) AS fileCount,
               SUM(CASE WHEN mediaType = 'IMAGE' THEN 1 ELSE 0 END) AS imageCount,
               SUM(CASE WHEN mediaType = 'VIDEO' THEN 1 ELSE 0 END) AS videoCount,
               SUM(sizeBytes) AS totalBytes,
               SUM(CASE WHEN quality = 'EXCELLENT' THEN 1 ELSE 0 END) AS excellentCount
        FROM recovered_files
        WHERE sessionId = :sessionId AND (:hideDuplicates = 0 OR isDuplicate = 0)
        GROUP BY folderPath, folderLabel
        ORDER BY fileCount DESC
        """
    )
    fun observeFolders(sessionId: Long, hideDuplicates: Boolean): Flow<List<FolderSummary>>

    @Query("SELECT COUNT(*) FROM recovered_files WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("SELECT id FROM recovered_files WHERE sessionId = :sessionId AND contentHash = :hash LIMIT 1")
    suspend fun findByHash(sessionId: Long, hash: String): Long?

    @Query("UPDATE recovered_files SET isDuplicate = 1, duplicateOfId = :originalId WHERE id = :id")
    suspend fun markDuplicate(id: Long, originalId: Long)

    @Query(
        """
        UPDATE recovered_files
        SET recovered = 1, recoveredUri = :uri, recoveredAt = :time
        WHERE id = :id
        """
    )
    suspend fun markRecovered(id: Long, uri: String, time: Long)

    @Query("DELETE FROM recovered_files WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("SELECT stagedPath FROM recovered_files WHERE sessionId = :sessionId AND isCarved = 1")
    suspend fun stagedPathsForSession(sessionId: Long): List<String?>
}

@Dao
interface RecoveryReportDao {

    @Insert
    suspend fun insert(report: RecoveryReportEntity): Long

    @Query("SELECT * FROM recovery_reports WHERE sessionId = :sessionId ORDER BY finishedAt DESC")
    fun observeForSession(sessionId: Long): Flow<List<RecoveryReportEntity>>

    @Query("SELECT * FROM recovery_reports ORDER BY finishedAt DESC LIMIT 1")
    suspend fun latest(): RecoveryReportEntity?
}
