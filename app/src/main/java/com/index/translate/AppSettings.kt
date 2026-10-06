package com.index.translate

import android.content.Context

/** 应用设置(SharedPreferences;模型选择、推理参数) */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 上下文长度(tokens) */
    var ctxTokens: Int
        get() = prefs.getInt(KEY_CTX, 4096)
        set(value) = prefs.edit().putInt(KEY_CTX, value.coerceIn(1024, 32768)).apply()

    /** 推理线程数;0 = 自动(大核优先) */
    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 0)
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

    /** 采样温度;0 = 贪心(与桌面版默认一致) */
    var temperature: Float
        get() = prefs.getFloat(KEY_TEMP, 0f)
        set(value) = prefs.edit().putFloat(KEY_TEMP, value).apply()

    /** 单次生成 token 上限 */
    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 1024)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value.coerceIn(64, 8192)).apply()

    /** 已选模型文件名 */
    var selectedModel: String?
        get() = prefs.getString(KEY_MODEL, null)
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /**
     * 连续加载尝试计数:加载前 +1,成功后清零。
     * 原生加载若被系统强杀(内存不足),进程内代码无从记录,用持久化计数
     * 防止「启动即自动加载 → 被杀 → 再启动再加载」的死循环。
     */
    var loadAttempts: Int
        get() = prefs.getInt(KEY_LOAD_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_LOAD_ATTEMPTS, value).apply()

    fun autoThreads(): Int {
        val manual = threads
        if (manual > 0) return manual
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(2, 6)
    }

    companion object {
        private const val KEY_CTX = "ctx_tokens"
        private const val KEY_THREADS = "threads"
        private const val KEY_TEMP = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_LOAD_ATTEMPTS = "load_attempts"
    }
}
