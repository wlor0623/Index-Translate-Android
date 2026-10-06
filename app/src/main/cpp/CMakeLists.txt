# Index-Translate-Android 原生层
# 依赖同目录 vendor 的 llama.cpp(b11439,支持 Qwen3.5 混合 SSM 架构)。
# 构建 GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS:多个 libggml-cpu-<variant>.so
# 运行时按 CPU 特性自动加载(与 llama.cpp 官方 Android 示例一致)。
cmake_minimum_required(VERSION 3.14...3.28)
project("idxtranslate" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_COMMON  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL       OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL          OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE        OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL     ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL        OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN        OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(idxtranslate SHARED idx_llama_jni.cpp)

find_library(log-lib log)
target_link_libraries(idxtranslate PRIVATE llama ${log-lib})
