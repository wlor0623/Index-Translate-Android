package com.index.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Index-Translate-2B 翻译 prompt 构造。
 *
 * 与 bilibili/Index-Translate 官方 inference/llm/translate.py(以及桌面版
 * Index-Translate-Desktop/translate.py)逐行对齐,模型按此格式训练,勿随意改动。
 */
object TranslationCore {

    /** 语言码 -> 中文名(与训练侧 prompt builder 一致) */
    val LANG_NAMES: Map<String, String> = linkedMapOf(
        "en" to "英语", "zh" to "中文", "de" to "德语", "fr" to "法语",
        "es" to "西班牙语", "ja" to "日语", "ko" to "韩语", "pt" to "葡萄牙语",
        "ru" to "俄语", "ar" to "阿拉伯语", "it" to "意大利语", "nl" to "荷兰语",
        "pl" to "波兰语", "ro" to "罗马尼亚语", "sv" to "瑞典语", "tr" to "土耳其语",
        "hi" to "印地语", "vi" to "越南语", "th" to "泰语", "id" to "印尼语",
        "ms" to "马来语", "fil" to "菲律宾语", "ukr_cyrl" to "乌克兰语",
        "fas_arab" to "波斯语", "ces_latn" to "捷克语", "ell_grek" to "希腊语",
        "dan_latn" to "丹麦语", "hun_latn" to "匈牙利语", "fin_latn" to "芬兰语",
        "nob_latn" to "书面挪威语", "slk_latn" to "斯洛伐克语", "bul_cyrl" to "保加利亚语",
    )

    /** UI 语言列表(自动检测放在第一位) */
    val SOURCE_LANGS: List<Pair<String, String>> =
        listOf("auto" to "自动检测") + LANG_NAMES.map { it.key to it.value }

    private fun langName(code: String): String = LANG_NAMES[code.lowercase()] ?: code

    /**
     * 术语表解析:"碳纤维:carbon fiber,王平仲:Wang Pingzhong"
     * 或 JSON 对象 {"碳纤维": "carbon fiber"} → ["碳纤维→carbon fiber", ...]
     */
    fun parseGlossaryTerms(glossaryInput: String?): List<String> {
        val input = glossaryInput?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()

        // JSON 对象形式
        if (input.startsWith("{") && input.endsWith("}")) {
            try {
                val obj = Json.decodeFromString<JsonObject>(input)
                return obj.entries.map { (k, v) -> "${k.trim()}→${v.toString().trim('"').trim()}" }
            } catch (_: Exception) {
                // 不是合法 JSON,继续按逗号分隔解析
            }
        }

        val pairs = mutableListOf<String>()
        for (item in input.replace('，', ',').split(',')) {
            val it0 = item.trim()
            if (it0.isEmpty()) continue
            when {
                "->" in it0 -> it0.split("->", limit = 2).let { (k, v) -> pairs.add("${k.trim()}→${v.trim()}") }
                "→" in it0 -> it0.split("→", limit = 2).let { (k, v) -> pairs.add("${k.trim()}→${v.trim()}") }
                ":" in it0 -> it0.split(":", limit = 2).let { (k, v) -> pairs.add("${k.trim()}→${v.trim()}") }
                "：" in it0 -> it0.split("：", limit = 2).let { (k, v) -> pairs.add("${k.trim()}→${v.trim()}") }
                else -> pairs.add(it0)
            }
        }
        return pairs
    }

    private fun cleanLine(line: String): String = line.trim().trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ' ')

    private fun isJsonObject(text: String): Boolean {
        val stripped = text.trim()
        if (!stripped.startsWith("{") || !stripped.endsWith("}")) return false
        return try {
            Json.decodeFromString<JsonObject>(stripped) is JsonObject
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 构造翻译 prompt(与官方 translate.py trans_prompt 逻辑一致)。
     *
     * @param text 源文本
     * @param targetLang 目标语言码,如 "en"
     * @param sourceLang 源语言码,"auto" 表示自动
     * @param hardConstraints 硬约束列表
     * @param softConstraints 软约束列表
     * @param glossary 术语表字符串
     * @param instruction 通用约束指令
     * @param genre 文体/领域
     */
    fun transPrompt(
        text: String,
        targetLang: String,
        sourceLang: String = "auto",
        hardConstraints: List<String> = emptyList(),
        softConstraints: List<String> = emptyList(),
        glossary: String = "",
        instruction: String = "",
        genre: String = "文本",
    ): String {
        val tgtName = langName(targetLang)
        val hasSource = sourceLang.isNotBlank() && !sourceLang.equals("auto", ignoreCase = true)
        val srcName = if (hasSource) langName(sourceLang) else ""

        val constraints = mutableListOf<String>()

        for (c in hardConstraints) {
            for (line in c.split('\n')) {
                val l = cleanLine(line)
                if (l.isEmpty()) continue
                constraints.add(if (l.startsWith("【硬性要求】")) l else "【硬性要求】$l")
            }
        }

        if (glossary.isNotBlank()) {
            val terms = parseGlossaryTerms(glossary)
            if (terms.isNotEmpty()) {
                constraints.add("【硬性要求】专名/术语对照: ${terms.joinToString("、")}")
            }
        }

        for (c in softConstraints) {
            for (line in c.split('\n')) {
                val l = cleanLine(line)
                if (l.isEmpty()) continue
                constraints.add(
                    if (l.startsWith("【注意】") || l.startsWith("【软性要求】")) l else "【注意】$l"
                )
            }
        }

        if (instruction.isNotBlank()) {
            for (line in instruction.split('\n')) {
                val l = cleanLine(line)
                if (l.isEmpty()) continue
                constraints.add(
                    if (l.startsWith("【硬性要求】") || l.startsWith("【注意】") || l.startsWith("【软性要求】")) l
                    else "【注意】$l"
                )
            }
        }

        if (constraints.isNotEmpty()) {
            val headerSrc = if (srcName.isNotEmpty()) "$srcName$genre" else genre
            val header = "请将以下${headerSrc}翻译成${tgtName}，并且严格遵循所有约束要求。"
            val reqLines = constraints.mapIndexed { i, c -> "${i + 1}. $c" }

            val isJson = isJsonObject(text)
            val suffix =
                if (isJson && constraints.any { "JSON" in it || "json" in it }) {
                    "请以相同的 JSON 格式输出翻译结果，key 保持不变，value 为对应译文。只输出 JSON，不要有任何额外说明。"
                } else {
                    "只输出译文，不要有任何额外说明。"
                }

            return buildString {
                append(header).append("\n\n")
                append("【源文】\n")
                append(text.trim()).append("\n\n")
                append("【约束要求】\n")
                append(reqLines.joinToString("\n")).append("\n\n")
                append(suffix)
            }
        }

        return if (srcName.isNotEmpty()) {
            "请将以下${srcName}文本翻译为$tgtName，直接输出翻译结果，不要进行任何解释。\n\n${text.trim()}"
        } else {
            "请将以下文本翻译为$tgtName，直接输出翻译结果，不要进行任何解释。\n\n${text.trim()}"
        }
    }

    /** 兜底去掉思考内容(本模型默认关闭思考,但保留兜底与桌面版一致) */
    fun stripThink(text: String): String {
        var t = text
        val idx = t.indexOf("</think>")
        if (idx >= 0) t = t.substring(idx + "</think>".length)
        t = t.trim()
        if (t.startsWith("<think>")) t = t.substring("<think>".length).trim()
        return t
    }

    /**
     * 构造 Qwen3.5 chat 格式的最终推理 prompt(单轮)。
     *
     * 与 GGUF 内置 jinja 模板渲染 [user] -> assistant 一致:
     * `<|im_start|>user\n{content}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`
     * (思考默认关闭,空 think 块预填)
     */
    fun toChatPrompt(transPrompt: String): String =
        "<|im_start|>user\n${transPrompt.trim()}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
}
