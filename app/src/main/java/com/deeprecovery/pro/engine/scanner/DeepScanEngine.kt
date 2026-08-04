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
import com.deeprecovery.pro.util.AppPrefs
import com.deeprecovery.pro.util.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val detectDuplicates: Boolean = true,
    /**
     * مجلدات اختارها المستخدم بنفسه. عند وجودها يقتصر الفحص عليها،
     * فيصبح محصوراً فيما يريده بالضبط وسريعاً.
     */
    val customFolders: Set<String> = emptySet(),
    /** أسماء مجلدات تُستبعد من المرور أصلاً (لقطات الشاشة، وسائط واتساب…). */
    val excludedDirNames: Set<String> = emptySet()
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
        private const val PROGRESS_INTERVAL_MS = 1500L
        /** أقصى معدّل لتحديث الواجهة أثناء الفحص. */
        private const val PUBLISH_INTERVAL_MS = 250L
        private const val MAX_CARVE_CANDIDATES = 400

        /**
         * أدنى أولوية يُقبل معها ملف للنحت — انظر [CarvePriority].
         * الدرجة 10 هي «ملف عادي»، وهي بالضبط ما نستبعده.
         */
        private const val MIN_CARVE_PRIORITY = 30
        /** الفحص الكامل يقبل كل شيء لمن يطلبه صراحةً. */
        private const val FULL_CARVE_PRIORITY = 0

        /** نتوقف عن الاستخراج قبل أن نخنق تخزين الجهاز. */
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
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
    private var totalFiles = 0
    /** سجلات بلا بايتات — تُستبعد بدل عرضها كنتائج مضلّلة. */
    private var unrecoverableCount = 0
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
    suspend fun runScan(request: ScanRequest, sessionId: Long = -1L): Long = coroutineScope {
        startTime = System.currentTimeMillis()
        val session = prepareSession(request, sessionId)
        val id = session.id

        // يُسجَّل فور إنشاء الجلسة لا بعد اكتمالها. كان يُكتب في نهاية
        // العامل فقط، فإن أُلغي الفحص أو أوقفه النظام بقي «آخر النتائج»
        // مشيراً إلى جلسة قديمة — وهذا سبب فتحه على شاشة فارغة.
        AppPrefs(context).lastSessionId = id

        _progress.value = ScanProgress(
            sessionId = id,
            status = ScanStatus.RUNNING,
            stageLabelRes = R.string.stage_preparing
        )

        // نبضة كل ثانية تُبقي الوقت المنقضي والوقت المتبقي يتحركان دائماً،
        // حتى في المراحل التي لا تُصدر أحداثاً — وإلا بدا التطبيق معلّقاً.
        val heartbeat = launch {
            while (isActive) {
                delay(1000)
                if (!paused) tickElapsed()
            }
        }

        try {
            val sources = buildSources(request, id)
            // المجموع يشمل مرحلة المرور ومرحلة النحت معاً، فلا تتجمّد النسبة
            val totalBytes = sources.sumOf { source ->
                source.totalBytes + (source as? ScanSource.Directory)?.carveBytes.orZero()
            }
            sessionDao.update(session.copy(totalBytes = totalBytes, status = ScanStatus.RUNNING))
            _progress.value = _progress.value.copy(
                totalBytes = totalBytes,
                totalFiles = totalFiles
            )

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
        } finally {
            heartbeat.cancel()
        }
    }

    private fun Long?.orZero(): Long = this ?: 0L

    /** يحدّث الزمن المنقضي والمتبقي دون كتابة في قاعدة البيانات. */
    private fun tickElapsed() {
        if (_progress.value.status != ScanStatus.RUNNING) return
        publish(force = true)
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

    private sealed class ScanSource(val key: String, val label: String, var totalBytes: Long) {
        class Directory(
            key: String,
            label: String,
            val dirs: List<File>,
            val location: ScanLocation
        ) : ScanSource(key, label, 0L) {
            /** حجم الملفات المرشّحة للنحت — يُحسب في مرحلة القياس. */
            var carveBytes: Long = 0
        }

        class LargeFile(val file: File, val location: ScanLocation) :
            ScanSource("file:${file.absolutePath}", file.name, file.length())

        class RawDevice(val path: String, val size: Long) :
            ScanSource("block:$path", path, size)
    }

    /**
     * يزيل المجلدات المتداخلة.
     *
     * اختيار «الذاكرة الداخلية» مع DCIM و Camera و WhatsApp يعني أن الملف
     * نفسه يقع تحت أكثر من جذر مختار، فيُفحص عدة مرات ويظهر مكرراً في
     * النتائج ويطيل الفحص أضعافاً. نُبقي الجذر الأعلى فقط.
     */
    private fun dedupeRoots(
        targets: List<com.deeprecovery.pro.util.StorageTarget>
    ): List<Pair<com.deeprecovery.pro.util.StorageTarget, File>> {
        val canonical = targets.flatMap { target ->
            target.directories.mapNotNull { dir ->
                runCatching { dir.canonicalFile }.getOrNull()?.let { target to it }
            }
        }

        val keptPaths = StorageUtils
            .dedupeOverlappingPaths(canonical.map { it.second.absolutePath })
            .toSet()

        val seen = mutableSetOf<String>()
        return canonical.filter { (_, dir) ->
            val path = dir.absolutePath.trimEnd('/')
            path in keptPaths && seen.add(path)
        }
    }

    private fun storageVolumes(): List<File> = buildList {
        add(StorageUtils.internalRoot())
        StorageUtils.sdCardRoot(context)?.let { add(it) }
    }

    private suspend fun buildSources(request: ScanRequest, sessionId: Long): List<ScanSource> {
        val sources = mutableListOf<ScanSource>()
        val targets = StorageUtils.resolveTargets(context)
            .filter { it.available && it.location in request.locations }

        val custom = request.customFolders
            .map(::File)
            .filter { it.isDirectory }

        if (custom.isNotEmpty()) {
            // اختيار المستخدم يتقدّم على كل شيء: نفحص ما طلبه فقط
            sources += ScanSource.Directory(
                key = "dir:custom",
                label = custom.joinToString(", ") { it.name },
                dirs = custom,
                location = ScanLocation.INTERNAL_STORAGE
            )

            // ألبوم محذوف لم تعد ملفاته في مكانها: بقاياه تعيش في ذاكرة
            // المصغّرات وسلة المهملات، فنضمّها وإلا لم يجد الفحص شيئاً
            if (request.depth != ScanDepth.DEEP) {
                val hotspots = StorageUtils.recoveryHotspots(storageVolumes())
                if (hotspots.isNotEmpty()) {
                    sources += ScanSource.Directory(
                        key = "dir:hotspots",
                        label = context.getString(R.string.stage_deleted_only),
                        dirs = hotspots,
                        location = ScanLocation.INTERNAL_STORAGE
                    )
                }
            }
        } else if (request.depth == ScanDepth.QUICK) {
            // الفحص السريع لا يمرّ على التخزين كاملاً: يقتصر على الأماكن
            // التي قد تحتوي بقايا محذوفة فعلاً
            val hotspots = StorageUtils.recoveryHotspots(storageVolumes())
            if (hotspots.isNotEmpty()) {
                sources += ScanSource.Directory(
                    key = "dir:hotspots",
                    label = context.getString(R.string.stage_deleted_only),
                    dirs = hotspots,
                    location = ScanLocation.INTERNAL_STORAGE
                )
            }
        } else {
            dedupeRoots(targets)
                .groupBy({ it.first }, { it.second })
                .forEach { (target, dirs) ->
                    sources += ScanSource.Directory(
                        key = "dir:${target.location.key}",
                        label = target.label,
                        dirs = dirs,
                        location = target.location
                    )
                }
        }

        if (request.mode == ScanMode.ROOT && ScanLocation.RAW_BLOCK in request.locations) {
            if (RootManager.isRootAvailable()) {
                RootManager.listPartitions()
                    .filter { it.isUserData }
                    .forEach { sources += ScanSource.RawDevice(it.path, it.sizeBytes) }
            }
        }

        // عدّ سريع يعطي مقاماً حقيقياً للنسبة وللوقت المتبقي
        val directories = sources.filterIsInstance<ScanSource.Directory>()
        if (directories.isNotEmpty()) {
            emitStage(R.string.stage_measuring, "")
            val scanner = FileSystemScanner()
            var total = 0
            var complete = true
            for (source in directories) {
                checkPause()
                val count = runCatching {
                    scanner.countFiles(source.dirs, request.excludedDirNames)
                }.getOrDefault(FileCount())
                total += count.files
                if (!count.complete) complete = false
            }
            // عدد ناقص لا يصلح مقاماً: نبقى على مؤشر غير محدد
            totalFiles = if (complete) total else 0
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
                excludedDirNames = request.excludedDirNames,
                onProgressFile = { file ->
                    checkPause()
                    if (isCarveCandidate(file, request.depth)) {
                        carveCandidates += file
                    }
                    bumpProcessed(file.length())
                    countScanned(file)
                    // بدون هذا يبقى الوقت المنقضي جامداً طوال أطول مرحلة
                    // في الفحص فيبدو التطبيق معلّقاً
                    emitProgress()
                },
                onFile = { discovered ->
                    persistDiscovered(sessionId, discovered, request.detectDuplicates)
                }
            )
        }.onFailure { if (it is CancellationException) throw it }

        // النحت يعمل في كل الأعماق: ذاكرة المصغّرات وسلال المهملات هي
        // أعلى مصادر الاستعادة بلا Root، وعددها صغير فلا يبطئ الفحص
        if (carveCandidates.isNotEmpty()) {
            emitStage(R.string.stage_carving, source.label)
            val carver = buildCarver(request)
            for (file in prioritizeCarveCandidates(carveCandidates)) {
                checkPause()
                if (!hasRoomForStaging()) break
                try {
                    FileRawSource(file).use { raw ->
                        carveInto(
                            sessionId = sessionId,
                            request = request,
                            carver = carver,
                            source = raw,
                            folderPath = folderFor(file),
                            folderLabel = file.parentFile?.name ?: source.label,
                            skipSelfAtOffsetZero = true
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    // ملف واحد لا يجب أن يُسقط الفحص كله
                    Log.w(TAG, "نفاد الذاكرة أثناء نحت ${file.name}", e)
                } catch (e: Throwable) {
                    Log.w(TAG, "تعذّر نحت ${file.name}", e)
                }
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

    /**
     * هل يستحق هذا الملف أن يُنحت؟
     *
     * صورة سليمة في `DCIM/Camera` ليست بيانات محذوفة؛ نحتها يعيد استخراج
     * ملف موجود أصلاً ويكلّف قراءة الملف كاملاً. كان الشرط الوحيد هو
     * الحجم، فدخلت عشرات الآلاف من الملفات العادية في قائمة النحت
     * وصار الفحص يستغرق عشرات الدقائق بلا نتيجة مفيدة.
     *
     * نقتصر الآن على مصادر البقايا الحقيقية — ذاكرة المصغّرات، سلال
     * المهملات، `LOST.DIR`، الملفات المخفية — ما لم يطلب المستخدم الفحص
     * الكامل صراحةً.
     */
    private fun isCarveCandidate(file: File, depth: ScanDepth): Boolean {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length < MIN_CARVE_TARGET) return false
        val floor = if (depth == ScanDepth.FULL) FULL_CARVE_PRIORITY else MIN_CARVE_PRIORITY
        return CarvePriority.of(file.absolutePath) >= floor
    }

    /** انظر [CarvePriority] لسبب هذا الترتيب. */
    private fun prioritizeCarveCandidates(candidates: List<File>): List<File> = candidates
        .sortedWith(
            compareByDescending<File> { CarvePriority.of(it.absolutePath) }
                .thenByDescending { it.length() }
        )
        .take(MAX_CARVE_CANDIDATES)

    private fun buildCarver(request: ScanRequest) = FileCarver(
        stagingDir = StorageUtils.stagingDir(context),
        signatures = SignatureRegistry.signaturesFor(request.includeImages, request.includeVideos),
        minFreeBytes = MIN_FREE_BYTES
    )

    private fun hasRoomForStaging(): Boolean =
        StorageUtils.freeSpace(StorageUtils.stagingDir(context)) > MIN_FREE_BYTES

    private suspend fun carveInto(
        sessionId: Long,
        request: ScanRequest,
        carver: FileCarver,
        source: RawSource,
        folderPath: String,
        folderLabel: String,
        startOffset: Long = 0L,
        targetId: Long? = null,
        skipSelfAtOffsetZero: Boolean = false
    ) {
        var lastCarveProcessed = 0L
        carver.carve(
            source = source,
            startOffset = startOffset,
            skipSelfAtOffsetZero = skipSelfAtOffsetZero,
            onProgress = { processed, absolute ->
                checkPause()
                targetId?.let { targetDao.updateProgress(it, absolute) }
                // نحتسب ما قرأه النحت كتقدّم أيضاً، وإلا توقف المؤشر أثناء
                // مرحلة الاستخراج وهي الأطول
                val delta = processed - lastCarveProcessed
                if (delta > 0) bumpProcessed(delta)
                lastCarveProcessed = processed
                emitProgress()
            },
            onFile = { carved ->
                persistCarved(sessionId, carved, folderPath, folderLabel, request.detectDuplicates)
            }
        )
    }

    // ------------------------------------------------------------- الحفظ

    /**
     * هل هذا العنصر قابل للاستعادة فعلاً؟
     *
     * سجل MediaStore قد يبقى بعد اختفاء بايتات الملف، فيظهر بحجم صفر:
     * لا صورة مصغّرة ولا تشغيل ولا شيء يُنسخ. عرضه كنتيجة قابلة
     * للاستعادة تضليل، فنستبعده.
     *
     * الاستثناء عناصر سلة مهملات النظام: لا يملك التطبيق فتحها، لكنها
     * تُستعاد فعلاً بأمر إلغاء الحذف.
     */
    private fun isRecoverable(discovered: DiscoveredFile): Boolean {
        if (discovered.note == "trashed") return true
        if (discovered.sizeBytes <= 0) return false

        discovered.uri?.let { raw ->
            val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return false
            return runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
            }.getOrDefault(false)
        }

        val file = discovered.path?.let(::File) ?: return false
        return file.isFile && file.canRead() && file.length() > 0
    }

    private suspend fun persistDiscovered(
        sessionId: Long,
        discovered: DiscoveredFile,
        detectDuplicates: Boolean
    ) {
        if (!isRecoverable(discovered)) {
            unrecoverableCount++
            return
        }

        val hash = discovered.path?.let { com.deeprecovery.pro.util.HashUtils.contentHash(File(it)) }

        // الملف نفسه يُكتشف مرة عبر MediaStore ومرة عبر المجلدات. سجل
        // MediaStore بلا مسار فلا بصمة محتوى له، فكان يفلت من كشف التكرار
        // ويظهر العنصر مرتين في النتائج. مفتاح الاسم+الحجم يربط النسختين.
        val weakKey = "w:${discovered.displayName.lowercase()}|${discovered.sizeBytes}"
        val duplicateOf = if (detectDuplicates) {
            hash?.takeIf { it.isNotEmpty() }?.let { seenHashes[it] } ?: seenHashes[weakKey]
        } else {
            null
        }

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
            contentUri = discovered.uri,
            isCarved = false,
            quality = discovered.quality,
            confidence = discovered.confidence,
            truncated = false,
            note = discovered.note,
            widthPx = discovered.width,
            heightPx = discovered.height,
            durationMs = discovered.durationMs,
            createdAt = discovered.createdAt,
            discoveredAt = System.currentTimeMillis(),
            contentHash = hash,
            isDuplicate = duplicateOf != null,
            duplicateOfId = duplicateOf
        )
        val id = fileDao.insert(entity)
        if (duplicateOf == null) {
            // نسجّل المفتاحين معاً: النسخة القادمة قد تحمل أحدهما فقط
            if (!hash.isNullOrEmpty()) seenHashes[hash] = id
            seenHashes[weakKey] = id
        }
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

    // ------------------------------------------------------------ التقدّم
    //
    // العدّادات تُحفظ في حقول عادية ويُنشر التقدّم على فترات.
    // دفع كائن تقدّم جديد لكل ملف يعني عشرات الآلاف من إعادة الرسم على
    // الخيط الرئيسي في فحص كبير — وهو بحد ذاته سبب رئيسي للبطء.

    private var scannedCount = 0
    private var foundCount = 0
    private var imagesCount = 0
    private var videosCount = 0
    private var duplicatesCount = 0
    private var processedBytes = 0L
    private var currentPath = ""
    private var stageRes = 0
    private var lastPublish = 0L

    private fun countFound(mediaType: MediaType, duplicate: Boolean, folder: String) {
        seenFolders += folder
        foundCount++
        if (mediaType == MediaType.IMAGE) imagesCount++
        if (mediaType == MediaType.VIDEO) videosCount++
        if (duplicate) duplicatesCount++
        publish()
    }

    /**
     * يسجّل ملفاً تمت معالجته.
     *
     * عدّاد الملفات المفحوصة والمسار الحالي هما مؤشر الحياة الحقيقي:
     * حجم ما سنمرّ عليه غير معروف مسبقاً فأي نسبة مئوية تخمين.
     */
    private fun countScanned(file: File) {
        scannedCount++
        file.parent?.let { currentPath = it }
        publish()
    }

    private fun bumpProcessed(bytes: Long) {
        processedBytes += bytes
    }

    /** ينشر لقطة التقدّم بحد أقصى [PUBLISH_INTERVAL_MS] مرة واحدة. */
    private fun publish(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) return
        lastPublish = now

        val current = _progress.value
        val elapsed = now - startTime
        val snapshot = current.copy(
            filesScanned = scannedCount,
            filesFound = foundCount,
            imagesFound = imagesCount,
            videosFound = videosCount,
            duplicatesFound = duplicatesCount,
            foldersFound = seenFolders.size,
            processedBytes = processedBytes,
            totalFiles = totalFiles,
            currentSource = currentPath,
            stageLabelRes = if (stageRes != 0) stageRes else current.stageLabelRes,
            elapsedMs = elapsed
        )
        _progress.value = snapshot.copy(etaMs = snapshot.estimateEta(elapsed))
    }

    private suspend fun emitStage(stage: Int, sourceName: String) {
        stageRes = stage
        if (sourceName.isNotEmpty()) currentPath = sourceName
        publish(force = true)
        emitProgress()
    }

    private suspend fun emitProgress() {
        val now = System.currentTimeMillis()
        if (now - lastProgressEmit < PROGRESS_INTERVAL_MS) return
        lastProgressEmit = now
        publish(force = true)

        val current = _progress.value
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
