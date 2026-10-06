package com.index.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TranslationCore 与桌面版(官方 translate.py)trans_prompt 的逐字节对拍测试。
 * 期望值由桌面版 Python 实现真实运行生成,保证移植一致。
 */
class TranslationCoreTest {

    @Test
    fun plainPrompt_autoSource() {
        val expected = "请将以下文本翻译为英语，直接输出翻译结果，不要进行任何解释。\n\n" +
            "你好，世界。今天天气不错，我们去公园散步吧。"
        assertEquals(
            expected,
            TranslationCore.transPrompt("你好，世界。今天天气不错，我们去公园散步吧。", "en"),
        )
    }

    @Test
    fun plainPrompt_withSource() {
        val expected = "请将以下英语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\n" +
            "Hello world"
        assertEquals(expected, TranslationCore.transPrompt("Hello world", "zh", "en"))
    }

    @Test
    fun glossaryPrompt() {
        val expected = "请将以下文本翻译成英语，并且严格遵循所有约束要求。\n\n" +
            "【源文】\n" +
            "王平仲采用了更加昂贵的碳纤维材料。\n\n" +
            "【约束要求】\n" +
            "1. 【硬性要求】专名/术语对照: 碳纤维→carbon fiber、王平仲→Wang Pingzhong\n\n" +
            "只输出译文，不要有任何额外说明。"
        assertEquals(
            expected,
            TranslationCore.transPrompt(
                "王平仲采用了更加昂贵的碳纤维材料。", "en",
                glossary = "碳纤维:carbon fiber,王平仲:Wang Pingzhong",
            ),
        )
    }

    @Test
    fun constraintsPrompt() {
        val expected = "请将以下商务邮件翻译成英语，并且严格遵循所有约束要求。\n\n" +
            "【源文】\n" +
            "今天下午的会议临时取消了。\n\n" +
            "【约束要求】\n" +
            "1. 【硬性要求】保留缩写\n" +
            "2. 【注意】语气正式一些\n" +
            "3. 【注意】面向海外客户\n\n" +
            "只输出译文，不要有任何额外说明。"
        assertEquals(
            expected,
            TranslationCore.transPrompt(
                "今天下午的会议临时取消了。", "en",
                hardConstraints = listOf("1. 保留缩写"),
                softConstraints = listOf("语气正式一些"),
                instruction = "面向海外客户",
                genre = "商务邮件",
            ),
        )
    }

    @Test
    fun jsonPrompt_keepsJsonSuffix() {
        val expected = "请将以下文本翻译成英语，并且严格遵循所有约束要求。\n\n" +
            "【源文】\n" +
            "{\"user_id\": 1024, \"message\": \"您的订单已支付完成。\"}\n\n" +
            "【约束要求】\n" +
            "1. 【硬性要求】保留 JSON 格式\n\n" +
            "请以相同的 JSON 格式输出翻译结果，key 保持不变，value 为对应译文。只输出 JSON，不要有任何额外说明。"
        assertEquals(
            expected,
            TranslationCore.transPrompt(
                "{\"user_id\": 1024, \"message\": \"您的订单已支付完成。\"}", "en",
                hardConstraints = listOf("保留 JSON 格式"),
            ),
        )
    }

    @Test
    fun glossaryParsing() {
        assertEquals(
            listOf("碳纤维→carbon fiber"),
            TranslationCore.parseGlossaryTerms("{\"碳纤维\": \"carbon fiber\"}"),
        )
        assertEquals(
            listOf("碳纤维→carbon fiber", "模型→model"),
            TranslationCore.parseGlossaryTerms("碳纤维->carbon fiber，模型→model"),
        )
        assertEquals(
            listOf("王平仲→Wang Pingzhong", "碳纤维→carbon fiber"),
            TranslationCore.parseGlossaryTerms("王平仲：Wang Pingzhong,碳纤维:carbon fiber"),
        )
    }

    @Test
    fun chatPromptFormat_matchesGgufTemplate() {
        val inner = TranslationCore.transPrompt("Hello world", "zh", "en")
        val expected = "<|im_start|>user\n" + inner.trim() +
            "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        assertEquals(expected, TranslationCore.toChatPrompt(inner))
    }

    @Test
    fun stripThink_removesThinkBlocks() {
        assertEquals("译文", TranslationCore.stripThink("<think>\n思考过程\n</think>\n\n译文"))
        assertEquals("译文", TranslationCore.stripThink("译文"))
    }

    @Test
    fun languageCount_matchesDesktop32() {
        assertEquals(32, TranslationCore.LANG_NAMES.size)
    }
}
