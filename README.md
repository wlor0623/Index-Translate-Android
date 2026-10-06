# Index-Translate-Android

[bilibili/Index-Translate](https://github.com/bilibili/Index-Translate) **Index-Translate-2B** 安卓端完全离线翻译,基于 llama.cpp(b11439)+ 官方 GGUF 量化,配合桌面版 [Index-Translate-Desktop](https://github.com/weixin_41961749/Index-Translate-Desktop) 使用。

- **完全离线**:推理在本地 CPU 完成,不联网、不上传任何数据(仅首次下载模型需要网络)
- **模型**:IndexTeam/Index-Translate-2B(Qwen3.5 混合 SSM 架构,150 种语言)
- **推理**:llama.cpp GGML_BACKEND_DL + CPU 多变体(arm64 NEON,运行时按 SoC 特性自动选核)
- **UI**:Kotlin + Jetpack Compose(Material 3),中文界面

## 功能(与桌面版对齐)

- 流式输出(打字机效果)、一键复制、停止生成、首字延迟/总耗时/tok/s 统计
- 源/目标语言切换(32 语种 + 自动检测)、语言交换
- 约束翻译:术语表(`碳纤维:carbon fiber`)、文体/领域、硬约束、软约束、通用指令(prompt 构造与官方 `translate.py` 逐行对齐,见单元测试对拍)
- 可展开查看实际发送的 Prompt
- 历史记录(本地 JSONL,最多 500 条),点击回填
- 模型管理:ModelScope 应用内下载(断点续传)、SAF 文件导入、多模型切换
- 推理设置:上下文长度、线程数、采样温度、生成上限

## 安装与使用

### 1. 安装 APK

```bash
gradlew assembleDebug     # 产物在 app/build/outputs/apk/debug/
adb install app/build/outputs/apk/debug/app-debug.apk
```

要求 Android 8.0+(arm64-v8a);**2B 模型推理约需 2.5-3 GB 可用内存**,建议 6 GB RAM 以上设备。

### 2. 获取模型(三选一)

| 方式 | 说明 |
|---|---|
| 应用内下载 | 「模型」页 → 在线下载(推荐 **Q4_K_M**,1.3 GB;Q8_0 近无损,2.1 GB),支持断点续传 |
| 文件导入 | 「模型」页 → 从文件导入,选择 .gguf(应用会复制到私有目录) |
| adb 推送 | `adb push Index-Translate-2B.Q4_K_M.gguf /sdcard/Android/data/com.index.translate/files/models/` |

模型直链(ModelScope,免登录):

```
https://modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/resolve/master/Index-Translate-2B.Q4_K_M.gguf
https://modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/resolve/master/Index-Translate-2B.Q8_0.gguf
```

### 3. 翻译

打开应用 → 等「模型」状态变为已加载(首次约 10-60 s)→「翻译」页输入文本 → 翻译。

2B Q4_K_M 在骁龙 8 系 CPU 上约 15-25 tok/s,日常短句翻译 2-4 s 出结果。

## 构建

### GitHub Actions(推荐,无需本地环境)

推送即自动构建:`.github/workflows/build-apk.yml` 在 ubuntu-latest 上跑单测 + 打 debug APK,产物在 Actions 的 Artifacts 下载(`Index-Translate-debug-apk`)。

推送 `v*` 标签会自动创建 GitHub Release 并附上 APK,手机浏览器可直接下载安装:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

### 本地构建

```bash
# 本机构建需要:JDK 17 + Android SDK(Platform 34、Build-Tools 34.0.0)
# NDK r27c 与 CMake 3.31.6(AGP 会经 sdkmanager 自动安装,或手动:)
sdkmanager "ndk;27.2.12479018" "cmake;3.31.6"

gradlew assembleDebug          # APK
gradlew testDebugUnitTest      # TranslationCore 与桌面版 Python 的对拍测试
```

国内网络下依赖走 Aliyun 镜像、Gradle 发行包走腾讯镜像(settings.gradle.kts / gradle-wrapper.properties 已配置);CI 上同样兼容(镜像不可达时自动回落 google/mavenCentral)。

## 目录

```
app/src/main/cpp/
  CMakeLists.txt           原生构建配置(GGML_BACKEND_DL + CPU 全变体)
  idx_llama_jni.cpp        JNI 封装:加载/流式生成/中断/释放
  llama.cpp/               vendor 的 llama.cpp b11439(仅 ggml/src/include/cmake/vendor)
app/src/main/java/com/index/translate/
  TranslationCore.kt       trans_prompt/术语表/语言表/strip_think(移植自官方 translate.py)
  LlamaEngine.kt           JNI 引擎封装 + 增量 UTF-8 解码
  ModelRepository.kt       模型扫描/选择/加载/SAF 导入/ModelScope 断点续传下载
  HistoryRepository.kt     历史记录(JSONL 持久化)
  AppSettings.kt           推理参数(SharedPreferences)
  AppViewModel.kt          应用状态编排
  ui/                      Compose 界面(翻译/历史/模型/设置)
app/src/test/              对拍桌面版 prompt 构造的单元测试
```

## 实现说明

- **思考模式关闭**:Index-Translate-2B 的 GGUF jinja 模板在未传 `enable_thinking` 时默认输出空 `<think></think>` 块。安卓端不走 jinja,直接按模板字节构造 chat prompt(见 `TranslationCore.toChatPrompt`),并保留 `strip_think` 兜底,与桌面版 `--jinja + enable_thinking:false` 行为一致。
- **prompt 规范**:`trans_prompt` 的约束格式(【硬性要求】/【注意】、术语对照 `k→v`、JSON 保留后缀)与官方评测实现逐行一致,由 `TranslationCoreTest` 保证。
- **流式回调**:JNI 每 token 回调 UTF-8 字节片段,native 侧做 UTF-8 边界切分(多字节字符不跨包截断),Kotlin 侧 `IncrementalDecoder` 增量解码,emoji/生僻字安全。
- **内存**:每次生成都清空 KV/SSM 状态(单轮翻译);切换模型先释放旧句柄再加载新模型,避免双模型内存叠加。
- **后端**:`GGML_BACKEND_DL=ON` 构建多个 `libggml-cpu-*.so`,运行时由 `ggml_backend_load_all_from_path(nativeLibraryDir)` 按 CPU 特性(dotprod/i8mm/sve/sme)加载最优变体——与 llama.cpp 官方 Android 示例一致。

## 与桌面版的差异

| | Desktop | Android |
|---|---|---|
| 推理 | llama-server(CUDA/CPU) | llama.cpp JNI(CPU NEON) |
| 模型量化 | Q8_0 / 9B Q4_K_M | Q4_K_M(推荐)/ Q6_K / Q8_0 |
| 上下文 | 8192 | 4096(可调至 16384) |
| 模型获取 | setup.sh | 应用内下载 / 导入 / adb push |

## License

应用代码遵循仓库上游许可;llama.cpp 为 MIT;模型权重与许可见 [IndexTeam/Index-Translate-2B-GGUF](https://modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF)。
