package com.index.translate

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 本地模型文件 */
data class LocalModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
)

/** 可下载的远端模型(ModelScope,免登录直链) */
data class RemoteModel(
    val fileName: String,
    val url: String,
    val approxSizeBytes: Long,
    val note: String,
)

/** 模型引擎状态 */
sealed interface ModelState {
    data object NoModel : ModelState
    data class Loading(val name: String) : ModelState
    data class Ready(val name: String, val handle: Long, val nCtx: Int, val loadMs: Long) : ModelState
    data class Failed(val name: String, val message: String) : ModelState
}

/** 下载状态 */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val fileName: String, val downloaded: Long, val total: Long) : DownloadState
    data class Done(val fileName: String) : DownloadState
    data class Error(val fileName: String, val message: String) : DownloadState
}

/**
 * 模型仓库:扫描 models 目录、选择与加载、SAF 导入、ModelScope 下载。
 *
 * 引擎句柄全应用唯一;切换模型先释放旧的再加载新的(串行,防内存叠加)。
 */
class ModelRepository(
    private val context: Context,
    private val settings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val modelsDir: File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("models")

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models

    private val _state = MutableStateFlow<ModelState>(ModelState.NoModel)
    val state: StateFlow<ModelState> = _state

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    private var downloadJob: Job? = null
    private var loadJob: Job? = null
    private var currentHandle: Long = 0

    val selectedName: String? get() = settings.selectedModel

    init {
        modelsDir.mkdirs()
        refresh()
        // 启动即加载已选模型;连续失败过(疑似被系统杀)则不再自动加载,等用户手动点
        val sel = settings.selectedModel
        if (sel != null && _models.value.any { it.name == sel }) {
            if (settings.loadAttempts >= 2) {
                _state.value = ModelState.Failed(
                    sel,
                    "上次加载未能完成(可能内存不足)。已跳过自动加载,点击模型重试(建议先清理后台应用)",
                )
            } else {
                loadModel(sel)
            }
        }
    }

    /** 扫描 models 目录下的 .gguf */
    fun refresh() {
        val list = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
            ?.sortedBy { it.name }
            ?.map { LocalModel(it.name, it.absolutePath, it.length()) }
            ?: emptyList()
        _models.value = list
        // 已选文件被删除时回退
        val sel = settings.selectedModel
        if (sel != null && list.none { it.name == sel } &&
            _state.value !is ModelState.Ready
        ) {
            _state.value = ModelState.NoModel
        }
    }

    /** 选择并加载模型(取消正在进行的生成与加载) */
    fun selectModel(name: String) {
        settings.selectedModel = name
        refresh()
        loadModel(name)
    }

    fun reload() {
        settings.selectedModel?.let { loadModel(it) }
    }

    private fun loadModel(name: String) {
        val file = modelsDir.resolve(name)
        if (!file.exists()) {
            _state.value = ModelState.NoModel
            return
        }
        // 已在加载/已加载同一模型则跳过
        when (val s = _state.value) {
            is ModelState.Loading -> if (s.name == name) return
            is ModelState.Ready -> if (s.name == name) return
            else -> {}
        }

        // 可用内存预检:模型本体 + 计算/上下文缓冲余量,不足时给提示而不是硬崩
        val need = file.length() + EXTRA_MEM_BYTES
        val avail = availMemBytes()
        if (avail in 1 until need) {
            android.util.Log.w(
                TAG,
                "memory check failed: need=${"%.2f".format(need / 1e9)}GB avail=${"%.2f".format(avail / 1e9)}GB",
            )
            _state.value = ModelState.Failed(
                name,
                "可用内存不足:约需 %.1f GB,当前仅 %.1f GB。请清理后台应用,或改小「设置→上下文长度」后重试"
                    .format(need / 1e9, avail / 1e9),
            )
            return
        }

        loadJob?.cancel()
        loadJob = scope.launch {
            // 先释放旧句柄(可能正在生成,先停)
            val old = currentHandle
            if (old != 0L) {
                LlamaEngine.stop(old)
            }
            withContext(Dispatchers.IO) { LlamaEngine.freeModel(old) }
            currentHandle = 0

            settings.loadAttempts += 1 // 原生加载被系统强杀时进程内无从善后,先记数
            _state.value = ModelState.Loading(name)
            val nThreads = settings.autoThreads()
            val nCtx = settings.ctxTokens
            android.util.Log.i(TAG, "loading $name (ctx=$nCtx threads=$nThreads)")
            val t0 = System.currentTimeMillis()
            // nBatch=256:压低混合 SSM 预填计算缓冲峰值
            val ptr = LlamaEngine.loadModel(file.absolutePath, nCtx, nThreads, nBatch = 256)
            val ms = System.currentTimeMillis() - t0
            if (ptr != 0L) {
                settings.loadAttempts = 0
                currentHandle = ptr
                android.util.Log.i(TAG, "model ready in ${ms}ms")
                _state.value = ModelState.Ready(name, ptr, LlamaEngine.nCtx(ptr), ms)
            } else {
                android.util.Log.e(TAG, "model load returned null")
                _state.value = ModelState.Failed(
                    name,
                    "模型加载失败(内存不足或文件损坏);可尝试清理后台应用、改小上下文,或重新下载",
                )
            }
        }
    }

    /** 当前可用内存(不含 cached 低水位保守值) */
    private fun availMemBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    fun unload() {
        loadJob?.cancel()
        val old = currentHandle
        currentHandle = 0
        _state.value = settings.selectedModel?.let { ModelState.Failed(it, "已手动卸载") } ?: ModelState.NoModel
        scope.launch(Dispatchers.IO) { LlamaEngine.freeModel(old) }
    }

    // ---------------- SAF 导入 ----------------

    /** 从 SAF Uri 导入 GGUF(带进度回调,返回导入后的文件名) */
    suspend fun importFromUri(
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf"
            val fixedName = if (name.endsWith(".gguf", true)) name else "$name.gguf"
            val dest = modelsDir.resolve(fixedName)
            val tmp = modelsDir.resolve("$fixedName.importing")
            val total = querySize(uri)
            var copied = 0L
            var lastReport = 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport > (16 shl 20)) {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                    onProgress(copied, total)
                }
            } ?: throw IOException("无法打开所选文件")

            if (tmp.renameTo(dest)) {
                refresh()
                fixedName
            } else {
                tmp.delete()
                throw IOException("导入失败:目标文件已存在")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(uri: Uri): Long {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        return -1L
    }

    // ---------------- ModelScope 下载 ----------------

    fun startDownload(remote: RemoteModel) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _download.value = DownloadState.Running(remote.fileName, 0, remote.approxSizeBytes)
            try {
                val result = downloadWithResume(remote) { copied, total ->
                    _download.value = DownloadState.Running(remote.fileName, copied, total)
                }
                refresh()
                _download.value = DownloadState.Done(result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _download.value = DownloadState.Idle
                    throw e
                }
                _download.value = DownloadState.Error(remote.fileName, e.message ?: "下载失败")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _download.value = DownloadState.Idle
        // 清理 .part 由用户在文件管理里处理即可(保留以便续传)
    }

    /**
     * 断点续传下载;.part 存好后重命名为最终文件。
     * 手动跟随 3xx 重定向:HttpURLConnection 跨域跳转不透传自定义头,
     * 每一跳重新附加 Range,避免续传被降级为全量下载导致文件损坏。
     */
    private suspend fun downloadWithResume(
        remote: RemoteModel,
        onProgress: (Long, Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val dest = modelsDir.resolve(remote.fileName)
        if (dest.exists() && dest.length() > 0) return@withContext remote.fileName

        val part = modelsDir.resolve("${remote.fileName}.part")
        var existing = if (part.exists()) part.length() else 0L

        while (isActive) {
            var url = URL(remote.url)
            var conn: HttpURLConnection? = null
            try {
                // 手动跟随重定向
                var redirects = 0
                while (true) {
                    val c = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) IndexTranslate/1.0")
                        setRequestProperty("Accept", "*/*")
                        if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                    }
                    conn = c
                    c.connect()
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location")
                            ?: throw IOException("重定向缺少 Location (HTTP $code)")
                        url = URL(url, loc)
                        c.disconnect()
                        conn = null
                        if (++redirects > 8) throw IOException("重定向次数过多")
                        continue
                    }
                    break
                }
                val c = conn ?: throw IOException("连接未建立")
                val code = c.responseCode

                if (code == 416) {
                    // 本地已下完整
                    c.disconnect()
                    if (part.renameTo(dest)) return@withContext remote.fileName
                    throw IOException("下载校验失败(416),请删除 .part 重试")
                }

                val resumed = code == 206
                if (!resumed) existing = 0
                val total = if (resumed) {
                    parseTotal(c.getHeaderField("Content-Range"), existing)
                } else {
                    c.contentLengthLong
                }

                c.inputStream.use { ins ->
                    java.io.RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(existing)
                        val buf = ByteArray(256 shl 10)
                        var copied = existing
                        var lastReport = 0L
                        while (true) {
                            if (!isActive) throw kotlinx.coroutines.CancellationException("cancelled")
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport > (4 shl 20)) {
                                lastReport = copied
                                onProgress(copied, total)
                            }
                        }
                        onProgress(copied, total.coerceAtLeast(copied))
                        if (total > 0 && copied < total) {
                            throw IOException("连接中断($copied/$total)")
                        }
                    }
                }
                c.disconnect()

                if (part.renameTo(dest)) return@withContext remote.fileName
                throw IOException("下载完成但重命名失败")
            } catch (e: IOException) {
                existing = part.length()
                if (!isActive) throw e
                delay(3000) // 3 秒后自动续传
            } finally {
                conn?.disconnect()
            }
        }
        throw kotlinx.coroutines.CancellationException("cancelled")
    }

    private fun parseTotal(contentRange: String?, existing: Long): Long {
        // 形如 "bytes 100-999/123456"
        val idx = contentRange?.lastIndexOf('/')
        if (idx != null && idx >= 0 && idx < contentRange.length - 1) {
            return contentRange.substring(idx + 1).toLongOrNull() ?: -1L
        }
        return existing - 1
    }

    companion object {
        private const val TAG = "ModelRepository"

        /** 模型权重之外的计算/上下文缓冲余量(qwen35 混合 SSM @ubatch256 实测量级) */
        private const val EXTRA_MEM_BYTES = 1_200_000_000L

        val REMOTE_MODELS = listOf(
            RemoteModel(
                "Index-Translate-2B.Q4_K_M.gguf",
                "https://modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/resolve/master/Index-Translate-2B.Q4_K_M.gguf",
                1_310_000_000,
                "推荐:1.3GB,手机端速度/内存均衡",
            ),
            RemoteModel(
                "Index-Translate-2B.Q6_K.gguf",
                "https://modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/resolve/master/Index-Translate-2B.Q6_K.gguf",
                1_610_000_000,
                "1.6GB,质量更高",
            ),
            RemoteModel(
                "Index-Translate-2B.Q8_0.gguf",
                "https://modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/resolve/master/Index-Translate-2B.Q8_0.gguf",
                2_080_000_000,
                "2.1GB,近无损;建议 8GB+ 内存设备",
            ),
        )
    }
}
