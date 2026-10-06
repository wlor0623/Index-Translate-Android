package com.index.translate

import android.content.Context
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * llama.cpp JNI 封装。
 *
 * - nativeInit:加载 ggml 后端变体(BACKEND_DL)+ 初始化,进程内一次
 * - nativeLoadModel:返回句柄(0=失败)
 * - nativeCompletion:同步流式生成,逐 token 回调 listener.onToken(UTF-8 字节片段)
 * - nativeStop:设置停止标志,生成循环在下个 token 前退出
 *
 * 句柄同一时刻只允许一个生成(native 侧有互斥);并发请求由上层仓库串行化。
 */
object LlamaEngine {

    /** 每个 token 的 UTF-8 字节片段回调 */
    fun interface TokenListener {
        fun onToken(bytes: ByteArray)
    }

    /** 完成码 */
    const val FINISH_EOG = 0
    const val FINISH_STOPPED = 1
    const val FINISH_MAX_TOKENS = 2

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            nativeInit(context.applicationInfo.nativeLibraryDir)
            initialized = true
        }
    }

    /** 加载模型;失败返回 0。应在后台线程调用(耗时 5-30s)。 */
    fun loadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int = 512): Long =
        nativeLoadModel(path, nCtx, nThreads, nBatch)

    fun freeModel(ptr: Long) {
        if (ptr != 0L) nativeFreeModel(ptr)
    }

    fun stop(ptr: Long) {
        if (ptr != 0L) nativeStop(ptr)
    }

    fun nCtx(ptr: Long): Int =
        if (ptr == 0L) 0 else nativeNCtx(ptr)

    /**
     * 流式生成(阻塞直至完成/中断;在调用线程上运行,须在 Dispatchers.Default 等)。
     *
     * @return 完成码 FINISH_*(负数为错误)
     */
    fun completion(
        ptr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onToken: (ByteArray) -> Unit,
    ): Int {
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        return nativeCompletion(ptr, bytes, maxTokens, temperature, TokenListener(onToken))
    }

    init {
        System.loadLibrary("idxtranslate")
    }

    // external 声明为 object 实例方法,JNI 符号名为 Java_com_index_translate_LlamaEngine_*
    private external fun nativeInit(nativeLibDir: String)
    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int): Long
    private external fun nativeFreeModel(ptr: Long)
    private external fun nativeStop(ptr: Long)
    private external fun nativeCompletion(
        ptr: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, listener: TokenListener,
    ): Int
    private external fun nativeNCtx(ptr: Long): Int
}

/**
 * 增量 UTF-8 解码器:处理跨 token 的多字节字符切分。
 * 每次生成新建一个;结束时调用 [flush] 补齐残余字节。
 */
class IncrementalDecoder {
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** 解码一个字节片段,追加到 sb */
    fun decode(chunk: ByteArray, sb: StringBuilder) {
        val inBuf = ByteBuffer.wrap(chunk)
        while (inBuf.hasRemaining()) {
            val outBuf = CharBuffer.allocate(1024)
            val result = decoder.decode(inBuf, outBuf, false)
            outBuf.flip()
            sb.append(outBuf)
            if (result.isUnderflow) break
            // overflow:继续循环消费剩余输入
        }
    }

    /** 生成分支结束后调用,冲出残余的半个字符 */
    fun flush(sb: StringBuilder) {
        try {
            val outBuf = CharBuffer.allocate(8)
            decoder.decode(ByteBuffer.wrap(ByteArray(0)), outBuf, true)
            val r = decoder.flush(outBuf)
            outBuf.flip()
            sb.append(outBuf)
            if (r.isOverflow) {
                outBuf.clear()
                decoder.flush(outBuf)
                outBuf.flip()
                sb.append(outBuf)
            }
        } catch (_: Exception) {
            // 尾部残字节解码失败,忽略
        }
    }
}
