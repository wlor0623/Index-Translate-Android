package com.index.translate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 生成统计 */
data class GenStats(
    val firstTokenMs: Long,
    val totalMs: Long,
    val tokens: Int,
) {
    val tokPerSec: Float
        get() = if (totalMs > 0) tokens * 1000f / totalMs else 0f
}

/** 翻译界面状态 */
data class TranslationUiState(
    val input: String = "",
    val output: String = "",
    val sourceLang: String = "en",
    val targetLang: String = "zh",
    val glossary: String = "",
    val genre: String = "文本",
    val hard: String = "",
    val soft: String = "",
    val instruction: String = "",
    val generating: Boolean = false,
    val stats: GenStats? = null,
    val error: String? = null,
    val showConstraints: Boolean = false,
    val promptPreview: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AppSettings(app)
    val history = HistoryRepository(app)
    val models = ModelRepository(app, settings)

    private val _ui = MutableStateFlow(TranslationUiState())
    val ui: StateFlow<TranslationUiState> = _ui

    private var genJob: Job? = null

    init {
        LlamaEngine.init(app)
    }

    // ---------------- 翻译表单 ----------------

    fun update(transform: (TranslationUiState) -> TranslationUiState) = _ui.update(transform)

    fun swapLanguages() {
        _ui.update { s ->
            // 源为自动检测时,目标反向没有意义,固定翻成另一种常用语
            val newTarget = if (s.sourceLang == "auto") {
                if (s.targetLang != "en") "en" else "zh"
            } else {
                s.sourceLang
            }
            s.copy(
                sourceLang = s.targetLang,
                targetLang = newTarget,
                input = s.output.ifBlank { s.input },
                output = if (s.output.isBlank()) "" else s.input,
            )
        }
    }

    // ---------------- 生成 ----------------

    fun translate() {
        val s = _ui.value
        if (s.generating) return
        if (s.input.isBlank()) return
        val st = models.state.value
        if (st !is ModelState.Ready) {
            _ui.update { it.copy(error = "模型未就绪,请先在「模型」页选择并加载") }
            return
        }

        val prompt = TranslationCore.transPrompt(
            text = s.input,
            targetLang = s.targetLang,
            sourceLang = s.sourceLang,
            hardConstraints = s.hard.lines().filter { it.isNotBlank() },
            softConstraints = s.soft.lines().filter { it.isNotBlank() },
            glossary = s.glossary,
            instruction = s.instruction,
            genre = s.genre.ifBlank { "文本" },
        )
        val chatPrompt = TranslationCore.toChatPrompt(prompt)

        _ui.update {
            it.copy(generating = true, output = "", stats = null, error = null, promptPreview = chatPrompt)
        }

        val handle = st.handle
        val maxTokens = settings.maxTokens
        val temperature = settings.temperature

        genJob = viewModelScope.launch(Dispatchers.Default) {
            val dec = IncrementalDecoder()
            val sb = StringBuilder()
            var firstTokenNs = 0L
            var tokens = 0
            val t0 = System.nanoTime()
            var finishCode = -999

            try {
                finishCode = LlamaEngine.completion(handle, chatPrompt, maxTokens, temperature) { bytes ->
                    tokens++
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    dec.decode(bytes, sb)
                    _ui.update { it.copy(output = sb.toString()) }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "推理异常") }
            }

            dec.flush(sb)
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            val firstMs = if (firstTokenNs != 0L) (firstTokenNs - t0) / 1_000_000 else 0
            val out = TranslationCore.stripThink(sb.toString())
            val cur = _ui.value

            val errText = when {
                finishCode < 0 && cur.error == null ->
                    when (finishCode) {
                        -1 -> "推理错误(详见 logcat)"
                        -2 -> "解码失败(上下文溢出?)"
                        -3 -> "输入过长,超出上下文"
                        else -> "未知错误($finishCode)"
                    }
                else -> cur.error
            }

            _ui.update {
                it.copy(
                    generating = false,
                    output = out,
                    stats = GenStats(firstMs, totalMs, tokens),
                    error = errText,
                )
            }

            if (finishCode >= 0 && out.isNotBlank()) {
                history.add(
                    HistoryItem(
                        id = UUID.randomUUID().toString(),
                        ts = System.currentTimeMillis(),
                        input = cur.input,
                        output = out,
                        sourceLang = cur.sourceLang,
                        targetLang = cur.targetLang,
                        glossary = cur.glossary,
                        genre = cur.genre,
                        tokens = tokens,
                        ms = totalMs,
                    )
                )
            }
        }
    }

    fun stopGeneration() {
        val st = models.state.value
        if (st is ModelState.Ready) LlamaEngine.stop(st.handle)
    }

    fun currentNCtx(): Int = LlamaEngine.nCtx(
        (models.state.value as? ModelState.Ready)?.handle ?: 0L
    )

    // ---------------- 历史 ----------------

    fun loadFromHistory(item: HistoryItem) {
        _ui.update {
            it.copy(
                input = item.input,
                output = item.output,
                sourceLang = item.sourceLang,
                targetLang = item.targetLang,
                glossary = item.glossary,
                genre = item.genre,
                stats = null,
                error = null,
            )
        }
    }

    // ---------------- 模型管理 ----------------

    fun deleteModel(model: LocalModel) {
        if (models.selectedName == model.name) {
            models.unload()
            settings.selectedModel = null
        }
        java.io.File(model.path).delete()
        models.refresh()
    }

    override fun onCleared() {
        genJob?.cancel()
        models.unload()
        super.onCleared()
    }
}
