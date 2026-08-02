package com.deeprecovery.pro.engine.scanner

import android.content.Context
import android.util.Log
import com.deeprecovery.pro.R
import com.deeprecovery.pro.data.db.AppDatabase
import com.deeprecovery.pro.data.db.RecoveredFileEntity
import com.deeprecovery.pro.data.db.ScanSessionEntity
import com.deeprecovery.pro.data.db.ScanTargetEntity
import com.deeprecovery.pro.data.model.MediaType
import com.deeprecovery.pro.data.model.RecoveryQuality
import com.deeprecovery.pro.data.model.ScanDepth
import com.deeprecovery.pro.data.model.ScanLocation
import com.deeprecovery.pro.data.model.ScanMode
import com.deeprecovery.pro.data.model.ScanStatus
import com.deeprecovery.pro.engine.carver.CarvedFile
import com.deeprecovery.pro.engine.carver.FileCarver
import com.deeprecovery.pro.engine.carver.FileRawSource
import com.deeprecovery.pro.engine.carver.RawSource
import com.deeprecovery.pro.engine.carver.SignatureRegistry
import com.deeprecovery.pro.engine.root.BlockDeviceRawSource
import com.deeprecovery.pro.engine.root.RootManager
import com.deeprecovery.pro.util.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** إعدادات جلسة فحص واحدة. */
data class ScanRequest(
    val mode: ScanMode,
    val depth: ScanDepth,
    val locations: Set<ScanLocation>,
    val includeImages: Boolean,
    val includeVideos: Boolean,
    val detectDuplicates: Boolean = true
)

/**
 * محرك الفحص العميق.
 *
 * يجمع ثلاث طبقات:
 *  1. تحليل MediaStore (سلة المهملات والعناصر المعلّقة) — يعمل بلا Root.
 *  2. المرور على المجلدات المتاحة والتعرّف على الملفات بالتوقيع لا بالاسم.
 *  3. File Carving على البيانات الخام: ملفات ضخمة، مساحات حرة، وفي وضع
 *     Root قراءة `/dev/block` مباشرة قطاعاً قطاعاً.
 *
 * يدعم **الإيقاف المؤقت والاستئناف**: يُحفظ تقدّم كل مصدر في قاعدة
 * البيانات، فيكمل الفحص من آخر إزاحة حتى بعد إغلاق التطبيق.
 */
class DeepScanEngine(private val context: Context) {

    companion object {
        private const val TAG = "DeepScanEngine"
        /** لا نطبّق النحت على الملفات الصغيرة جداً. */
        private const val MIN_CARVE_TARGET = 1L * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 400L
    }

    private val db = AppDatabase.get(context)
    private val sessionDao = db.scanSessionDao()
    private val targetDao = db.scanTargetDao()
    private val fileDao = db.recoveredFileDao()

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    @Volatile
    private var paused = false
    private val pauseLock = Mutex()

    private var startTime = 0L
    private var lastProgressEmit = 0L
    private val seenHashes = mutableMapOf<String, Long>()
    private val seenFolders = mutableSetOf<String>()

    fun pause() {
        paused = true
        _progress.value = _progress.value.copy(status = ScanStatus.PAUSED)
    }

    fun resume() {
        paused = false
        _progress.value = _progress.value.copy(status = ScanStatus.RUNNING)
    }

    val isPaused: Boolean get() = paused

    /**
     * ينفّذ الفحص كاملاً.
     *
     * @param sessionId جلسة موجودة للاستئناف، أو -1 لإنشاء جلسة جديدة.
     * @return معرّف الجلسة.
     */
    suspend fun runScan(request: ScanRequest, sessionId: Long = -1L): Long {
        startTime = System.currentTimeMillis()
        val session = prepareSession(request, sessionId)
        val id = session.id

        _progress.value = ScanProgress(
            sessionId = id,
            status = ScanStatus.RUNNING,
            stageLabelRes = R.string.stage_preparing
        )

        return try {
            val sources = buildSources(request, id)
            val totalBytes = sources.sumOf { it.totalBytes }
            sessionDao.update(session.copy(totalBytes = totalBytes, status = ScanStatus.RUNNING))
            _progress.value = _progress.value.copy(totalBytes = totalBytes)

            // الطبقة 1: MediaStore (سريعة، تُنفَّذ دائماً)
            scanMediaStore(id, request)

            // الطبقة 2 و 3
            for (source in sources) {
                checkPause()
                when (source) {
                    is ScanSource.Directory -> scanDirectory(id, request, source)
                    is ScanSource.RawDevice -> carveRawSource(id, request, source)
                    is ScanSource.LargeFile -> carveRawSource(id, request, source)
                }
            }

            finish(id, ScanStatus.COMPLETED, null)
            id
        } catch (e: CancellationException) {
            finish(id, if (paused) ScanStatus.PAUSED else ScanStatus.CANCELLED, null)
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "فشل الفحص", e)
            finish(id, ScanStatus.FAILED, e.message)
            id
        }
    }

    // ------------------------------------------------------------- الجلسة

    private suspend fun prepareSession(request: ScanRequest, sessionId: Long): ScanSessionEntity {
        if (sessionId > 0) {
            sessionDao.getById(sessionId)?.let { existing ->
                sessionDao.update(existing.copy(status = ScanStatus.RUNNING))
                return existing.copy(status = ScanStatus.RUNNING)
            }
        }
        val entity = ScanSessionEntity(
            startedAt = System.currentTimeMillis(),
            mode = request.mode,
            depth = request.depth,
            locations = request.locations.joinToString(",") { it.key },
            includeImages = request.includeImages,
            includeVideos = request.includeVideos,
            status = ScanStatus.RUNNING
        )
        val id = sessionDao.insert(entity)
        return entity.copy(id = id)
    }

    private suspend fun finish(sessionId: Long, status: ScanStatus, error: String?) {
        val current = _progress.value
        sessionDao.getById(sessionId)?.let {
            sessionDao.update(
                it.copy(
                    status = status,
                    finishedAt = System.currentTimeMillis(),
                    processedBytes = current.processedBytes,
                    filesFound = current.filesFound,
                    imagesFound = current.imagesFound,
                    videosFound = current.videosFound,
                    duplicatesFound = current.duplicatesFound,
                    errorMessage = error
                )
            )
        }
        _progress.value = current.copy(status = status, errorMessage = error)
    }

    // ------------------------------------------------------------ المصادر

    private sealed class ScanSource(val key: String, val label: String, val totalBytes: Long) {
        class Directory(
            key: String,
            label: String,
            val dirs: List<File>,
            val location: ScanLocation
        ) : ScanSource(key, label, dirs.sumOf { runCatching { it.totalSpace }.getOrDefault(0L) })

        class LargeFile(val file: File, val location: ScanLocation) :
            ScanSource("file:${file.absolutePath}", file.name, file.length())

        class RawDevice(val path: String, val size: Long) :
            ScanSource("block:$path", path, size)
    }

    private suspend fun buildSources(request: ScanRequest, sessionId: Long): List<ScanSource> {
        val sources = mutableListOf<ScanSource>()
        val targets = StorageUtils.resolveTargets(context)

        targets.filter { it.available && it.location in request.locations }.forEach { target ->
            sources += ScanSource.Directory(
                key = "dir:${target.location.key}",
                label = target.label,
                dirs = target.directories,
                location = target.location
            )
        }

        if (request.mode == ScanMode.ROOT && ScanLocation.RAW_BLOCK in request.locations) {
            if (RootManager.isRootAvailable()) {
                RootManager.listPartitions()
                    .filter { it.isUserData }
                    .forEach { sources += ScanSource.RawDevice(it.path, it.sizeBytes) }
            }
        }

        // نسجّل الأهداف لتتبع التقدّم والاستئناف
        sources.forEach { source ->
            targetDao.insert(
                ScanTargetEntity(
                    sessionId = sessionId,
                    sourceKey = source.key,
                    displayName = source.label,
                    locationKey = source.key.substringAfter(':'),
                    totalBytes = source.totalBytes
                )
            )
        }
        return sources
    }

    // ------------------------------------------------------- الطبقة الأولى

    private suspend fun scanMediaStore(sessionId: Long, request: ScanRequest) {
        emitStage(R.string.stage_mediastore, "MediaStore")
        val scanner = MediaStoreScanner(context)
        val found = runCatching {
            scanner.scanTrashedAndPending(request.includeImages, request.includeVideos)
        }.getOrDefault(emptyList())

        found.forEach { discovered ->
            checkPause()
            persistDiscovered(sessionId, discovered, request.detectDuplicates)
        }
    }

    // ------------------------------------------------------- الطبقة الثانية

    private suspend fun scanDirectory(
        sessionId: Long,
        request: ScanRequest,
        source: ScanSource.Directory
    ) {
        emitStage(R.string.stage_folders, source.label)
        val target = targetDao.find(sessionId, source.key)
        if (target?.completed == true) return

        val walker = FileSystemScanner()
        val carveCandidates = mutableListOf<File>()

        runCatching {
            walker.walk(
                roots = source.dirs,
                includeImages = request.includeImages,
                includeVideos = request.includeVideos,
                onProgressFile = { file ->
                    checkPause()
                    if (request.depth != ScanDepth.QUICK && file.length() >= MIN_CARVE_TARGET) {
                        carveCandidates += file
                    }
                    bumpProcessed(file.length())
                },
                onFile = { discovered ->
                    persistDiscovered(sessionId, discovered, request.detectDuplicates)
                }
            )
        }.onFailure { if (it is CancellationException) throw it }

        // الفحص العميق: نحت داخل الملفات الكبيرة (قد تحوي بقايا ملفات سابقة)
        if (request.depth != ScanDepth.QUICK) {
            emitStage(R.string.stage_carving, source.label)
            val carver = buildCarver(request)
            for (file in carveCandidates.take(400)) {
                checkPause()
                runCatching {
                    FileRawSource(file).use { raw ->
                        carveInto(sessionId, request, carver, raw, folderFor(file), file.parentFile?.name ?: source.label)
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

        target?.let { targetDao.markCompleted(it.id) }
    }

    // ------------------------------------------------------- الطبقة الثالثة

    private suspend fun carveRawSource(
        sessionId: Long,
        request: ScanRequest,
        source: ScanSource
    ) {
        val target = targetDao.find(sessionId, source.key)
        if (target?.completed == true) return
        val startOffset = target?.processedBytes ?: 0L

        val raw: RawSource = when (source) {
            is ScanSource.RawDevice -> BlockDeviceRawSource(source.path, source.size)
            is ScanSource.LargeFile -> FileRawSource(source.file)
            else -> return
        }

        emitStage(R.string.stage_raw_sectors, source.label)
        val folderLabel = when (source) {
            is ScanSource.RawDevice -> source.path.substringAfterLast('/')
            else -> source.label
        }
        val folder = "DeepRecovery/${folderLabel}"

        raw.use {
            carveInto(sessionId, request, buildCarver(request), it, folder, folderLabel, startOffset, target?.id)
        }
        target?.let { targetDao.markCompleted(it.id) }
    }

    private fun buildCarver(request: ScanRequest) = FileCarver(
        stagingDir = StorageUtils.stagingDir(context),
        signatures = SignatureRegistry.signaturesFor(request.includeImages, request.includeVideos)
    )

    private suspend fun carveInto(
        sessionId: Long,
        request: ScanRequest,
        carver: FileCarver,
        source: RawSource,
        folderPath: String,
        folderLabel: String,
        startOffset: Long = 0L,
        targetId: Long? = null
    ) {
        carver.carve(
            source = source,
            startOffset = startOffset,
            onProgress = { _, absolute ->
                checkPause()
                targetId?.let { targetDao.updateProgress(it, absolute) }
                emitProgress()
            },
            onFile = { carved ->
                persistCarved(sessionId, carved, folderPath, folderLabel, request.detectDuplicates)
            }
        )
    }

    // ------------------------------------------------------------- الحفظ

    private suspend fun persistDiscovered(
        sessionId: Long,
        discovered: DiscoveredFile,
        detectDuplicates: Boolean
    ) {
        val hash = discovered.path?.let { com.deeprecovery.pro.util.HashUtils.contentHash(File(it)) }
        val duplicateOf = if (detectDuplicates && !hash.isNullOrEmpty()) seenHashes[hash] else null

        val entity = RecoveredFileEntity(
            sessionId = sessionId,
            displayName = discovered.displayName,
            extension = discovered.displayName.substringAfterLast('.', ""),
            mimeType = discovered.mimeType,
            mediaType = discovered.mediaType,
            sizeBytes = discovered.sizeBytes,
            folderPath = discovered.folderPath,
            folderLabel = discovered.folderLabel,
            sourceName = discovered.path ?: discovered.uri.orEmpty(),
            sourceOffset = -1L,
            stagedPath = discovered.path,
            isCarved = false,
            quality = discovered.quality,
            confidence = discovered.confidence,
            truncated = false,
            note = discovered.note,
            widthPx = discovered.width,
            heightPx = discovered.height,
            durationMs = discovered.durationMs,
            discoveredAt = System.currentTimeMillis(),
            contentHash = hash,
            isDuplicate = duplicateOf != null,
            duplicateOfId = duplicateOf
        )
        val id = fileDao.insert(entity)
        if (!hash.isNullOrEmpty() && duplicateOf == null) seenHashes[hash] = id
        countFound(entity.mediaType, duplicateOf != null, entity.folderPath)
    }

    private suspend fun persistCarved(
        sessionId: Long,
        carved: CarvedFile,
        folderPath: String,
        folderLabel: String,
        detectDuplicates: Boolean
    ) {
        val duplicateOf = if (detectDuplicates && carved.contentHash.isNotEmpty()) {
            seenHashes[carved.contentHash]
        } else {
            null
        }

        val subFolder = if (carved.mediaType == MediaType.VIDEO) "Videos" else "Images"
        val fullFolder = "$folderPath/$subFolder"

        val entity = RecoveredFileEntity(
            sessionId = sessionId,
            displayName = carved.stagedFile.name,
            extension = carved.extension,
            mimeType = carved.mimeType,
            mediaType = carved.mediaType,
            sizeBytes = carved.sizeBytes,
            folderPath = fullFolder,
            folderLabel = folderLabel,
            sourceName = carved.sourceName,
            sourceOffset = carved.sourceOffset,
            stagedPath = carved.stagedFile.absolutePath,
            isCarved = true,
            quality = carved.quality,
            confidence = carved.confidence,
            truncated = carved.truncated,
            note = carved.note,
            widthPx = carved.width,
            heightPx = carved.height,
            durationMs = carved.durationMs,
            discoveredAt = System.currentTimeMillis(),
            contentHash = carved.contentHash,
            isDuplicate = duplicateOf != null,
            duplicateOfId = duplicateOf
        )
        val id = fileDao.insert(entity)
        if (carved.contentHash.isNotEmpty() && duplicateOf == null) {
            seenHashes[carved.contentHash] = id
        }
        countFound(carved.mediaType, duplicateOf != null, fullFolder)
    }

    // ------------------------------------------------------------ التقدّم

    private fun countFound(mediaType: MediaType, duplicate: Boolean, folder: String) {
        seenFolders += folder
        val current = _progress.value
        _progress.value = current.copy(
            filesFound = current.filesFound + 1,
            imagesFound = current.imagesFound + if (mediaType == MediaType.IMAGE) 1 else 0,
            videosFound = current.videosFound + if (mediaType == MediaType.VIDEO) 1 else 0,
            duplicatesFound = current.duplicatesFound + if (duplicate) 1 else 0,
            foldersFound = seenFolders.size
        )
    }

    private fun bumpProcessed(bytes: Long) {
        val current = _progress.value
        _progress.value = current.copy(processedBytes = current.processedBytes + bytes)
    }

    private suspend fun emitStage(stageRes: Int, sourceName: String) {
        _progress.value = _progress.value.copy(
            stageLabelRes = stageRes,
            currentSource = sourceName
        )
        emitProgress()
    }

    private suspend fun emitProgress() {
        val now = System.currentTimeMillis()
        if (now - lastProgressEmit < PROGRESS_INTERVAL_MS) return
        lastProgressEmit = now

        val current = _progress.value
        val elapsed = now - startTime
        val eta = if (current.processedBytes > 0 && current.totalBytes > current.processedBytes) {
            val rate = current.processedBytes.toDouble() / elapsed.coerceAtLeast(1)
            ((current.totalBytes - current.processedBytes) / rate).toLong()
        } else {
            -1L
        }
        _progress.value = current.copy(elapsedMs = elapsed, etaMs = eta)

        sessionDao.updateProgress(
            id = current.sessionId,
            processed = current.processedBytes,
            total = current.totalBytes,
            files = current.filesFound,
            images = current.imagesFound,
            videos = current.videosFound
        )
    }

    /** نقطة تحقق للإيقاف المؤقت — تُستدعى داخل كل حلقة فحص. */
    private suspend fun checkPause() {
        while (paused) {
            pauseLock.withLock { delay(200) }
        }
    }

    private fun folderFor(file: File): String {
        val root = StorageUtils.internalRoot().absolutePath
        val parent = file.parentFile?.absolutePath ?: return "Unknown"
        return when {
            parent.startsWith(root) -> parent.removePrefix(root).trim('/').ifEmpty { "Root" }
            else -> parent.trim('/')
        }
    }
}
