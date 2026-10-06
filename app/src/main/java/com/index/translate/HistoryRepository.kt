package com.index.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryItem(
    val id: String,
    val ts: Long,
    val input: String,
    val output: String,
    val sourceLang: String,
    val targetLang: String,
    val glossary: String = "",
    val genre: String = "文本",
    val tokens: Int = 0,
    val ms: Long = 0,
)

/**
 * 翻译历史:内存 StateFlow + filesDir/history.jsonl 持久化(最多 500 条)。
 */
class HistoryRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "history.jsonl")
    private val _items = mutableListOf<HistoryItem>()

    // 简单并发保护:所有读写都在主线程或加锁;这里用 synchronized 足够
    private val lock = Any()

    val items: List<HistoryItem>
        get() = synchronized(lock) { _items.toList() }

    init {
        runCatching { loadFromDisk() }
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val loaded = file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString<HistoryItem>(line) }.getOrNull()
            }
        synchronized(lock) { _items.addAll(loaded) }
    }

    fun add(item: HistoryItem) {
        synchronized(lock) {
            _items.add(0, item)
            while (_items.size > MAX_ITEMS) _items.removeAt(_items.size - 1)
        }
        persistAsync()
    }

    fun remove(id: String) {
        synchronized(lock) { _items.removeAll { it.id == id } }
        persistAsync()
    }

    fun clear() {
        synchronized(lock) { _items.clear() }
        persistAsync()
    }

    private fun persistAsync() {
        Thread {
            runCatching {
                val snapshot = synchronized(lock) { _items.toList() }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                    for (item in snapshot) w.write(json.encodeToString(item) + "\n")
                }
                tmp.renameTo(file)
            }
        }.start()
    }

    companion object {
        private const val MAX_ITEMS = 500
    }
}
