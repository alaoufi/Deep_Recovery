package com.deeprecovery.pro.engine.root

import android.util.Log
import com.deeprecovery.pro.engine.carver.RawSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream

/** قسم تخزين خام على مستوى الجهاز الكتلي. */
data class BlockPartition(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val mountPoint: String?
) {
    /** الأقسام التي تحتوي بيانات المستخدم عادةً. */
    val isUserData: Boolean
        get() = name.contains("userdata", true) ||
            name.contains("data", true) ||
            mountPoint == "/data"
}

/**
 * إدارة صلاحيات الـ Root والوصول إلى الأجهزة الكتلية.
 *
 * الوضع المتقدّم يقرأ `/dev/block/...` مباشرة، وهذا هو الفحص العميق
 * الحقيقي: يمرّ على القطاعات الخام بما فيها المساحة غير المخصصة
 * (Unallocated) التي تبقى فيها بقايا الملفات بعد حذفها.
 */
object RootManager {

    private const val TAG = "RootManager"
    private const val SU = "su"

    @Volatile
    private var cachedAvailability: Boolean? = null

    /** هل يستجيب الجهاز لطلب صلاحيات الجذر؟ */
    suspend fun isRootAvailable(forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            cachedAvailability?.takeIf { !forceRefresh }?.let { return@withContext it }
            val available = runCatching {
                val output = exec("id")
                output.contains("uid=0")
            }.getOrDefault(false)
            cachedAvailability = available
            available
        }

    /** ينفّذ أمراً بصلاحيات الجذر ويعيد المخرجات النصية. */
    fun exec(command: String): String {
        val process = ProcessBuilder(SU).redirectErrorStream(true).start()
        return try {
            DataOutputStream(process.outputStream).use { out ->
                out.writeBytes("$command\n")
                out.writeBytes("exit\n")
                out.flush()
            }
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            process.waitFor()
            output
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * يسرد الأقسام الكتلية المتاحة عبر `/proc/partitions` ونقاط التركيب.
     */
    suspend fun listPartitions(): List<BlockPartition> = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext emptyList()

        val mounts = runCatching { parseMounts(exec("cat /proc/mounts")) }
            .getOrDefault(emptyMap())

        val partitions = runCatching {
            exec("cat /proc/partitions")
                .lineSequence()
                .drop(1)
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 4) return@mapNotNull null
                    val blocks = parts[2].toLongOrNull() ?: return@mapNotNull null
                    val name = parts[3]
                    val path = "/dev/block/$name"
                    BlockPartition(
                        path = path,
                        name = name,
                        sizeBytes = blocks * 1024L,
                        mountPoint = mounts[path]
                    )
                }
                .filter { it.sizeBytes > 64L * 1024 * 1024 }
                .toList()
        }.getOrElse {
            Log.w(TAG, "تعذّر قراءة /proc/partitions", it)
            emptyList()
        }

        // نضع أقسام بيانات المستخدم أولاً لأنها الأعلى احتمالاً للنتائج
        partitions.sortedByDescending { if (it.isUserData) 1 else 0 }
    }

    private fun parseMounts(raw: String): Map<String, String> = buildMap {
        raw.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].startsWith("/dev/")) {
                put(resolveDevice(parts[0]), parts[1])
            }
        }
    }

    private fun resolveDevice(device: String): String = runCatching {
        File(device).canonicalPath
    }.getOrDefault(device)

    /** يحاول قراءة أول بايت من المسار للتأكد من إمكانية الوصول. */
    suspend fun canRead(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            exec("dd if=$path bs=512 count=1 2>/dev/null | wc -c").trim().toLongOrNull() ?: 0L
        }.getOrDefault(0L) > 0L
    }
}

/**
 * مصدر خام يقرأ جهازاً كتلياً (`/dev/block/...`) عبر `su -c dd`.
 *
 * لا يتوفر وصول عشوائي حقيقي عبر الأنبوب، لذا تُنفَّذ كل قراءة كأمر `dd`
 * مستقل. نستخدم كتلاً بحجم 4096 بايت مع `skip` بالكتل — لأن `bs=1` بطيء
 * جداً — ثم نتخطّى الفائض داخل التطبيق للوصول إلى الإزاحة المطلوبة تماماً.
 *
 * الأداء مقبول لأن محرك النحت يقرأ قطعاً بحجم عدة ميغابايت في كل مرة.
 */
class BlockDeviceRawSource(
    private val devicePath: String,
    override val length: Long,
    override val displayName: String = devicePath
) : RawSource {

    private companion object {
        const val BLOCK_SIZE = 4096L
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0) return -1
        if (this.length in 1..position) return -1

        val count = if (this.length > 0) {
            minOf(length.toLong(), this.length - position).toInt()
        } else {
            length
        }
        if (count <= 0) return -1

        val alignedStart = (position / BLOCK_SIZE) * BLOCK_SIZE
        val skipInside = (position - alignedStart).toInt()
        val blocksNeeded = (skipInside + count + BLOCK_SIZE - 1) / BLOCK_SIZE
        val command = "dd if=$devicePath bs=$BLOCK_SIZE " +
            "skip=${alignedStart / BLOCK_SIZE} count=$blocksNeeded 2>/dev/null"

        return runCatching { runDd(command, buffer, offset, count, skipInside) }
            .getOrDefault(-1)
    }

    private fun runDd(
        command: String,
        buffer: ByteArray,
        offset: Int,
        count: Int,
        skipInside: Int
    ): Int {
        val process = ProcessBuilder("su", "-c", command).start()
        return try {
            val stream: InputStream = process.inputStream
            var skipped = 0L
            val scratch = ByteArray(BLOCK_SIZE.toInt())
            while (skipped < skipInside) {
                val want = minOf(skipInside - skipped, scratch.size.toLong()).toInt()
                val read = stream.read(scratch, 0, want)
                if (read <= 0) break
                skipped += read
            }
            var total = 0
            while (total < count) {
                val read = stream.read(buffer, offset + total, count - total)
                if (read <= 0) break
                total += read
            }
            runCatching { stream.close() }
            process.waitFor()
            if (total > 0) total else -1
        } finally {
            runCatching { process.destroy() }
        }
    }

    override fun close() = Unit
}
