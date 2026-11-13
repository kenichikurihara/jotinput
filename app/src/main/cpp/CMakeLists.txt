cmake_minimum_required(VERSION 3.22.1)
project("myasr_native" C CXX)

find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/llama_cpp_src")
set(ONNX_INC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/onnxruntime_include")
set(ONNX_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/models/futo_swipe/lib/${ANDROID_ABI}")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable server" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Disable tools" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "CPU architecture for ARM" FORCE)
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(GGML_AVX ON CACHE BOOL "Enable AVX" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "Enable AVX2" FORCE)
    set(GGML_F16C ON CACHE BOOL "Enable F16C" FORCE)
    set(GGML_FMA ON CACHE BOOL "Enable FMA" FORCE)
    set(GGML_SSE42 ON CACHE BOOL "Enable SSE4.2" FORCE)
    add_compile_options(-mavx2 -mfma -mf16c)
endif()

add_subdirectory("${LLAMA_DIR}" llama_cpp EXCLUDE_FROM_ALL)

# Prebuilt ONNX Runtime from sherpa-onnx AAR
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNX_LIB_DIR}/libonnxruntime.so"
)

# Combined native library: LLM reranker + FUTO Swipe engine + English Spell engine
add_library(myasr_llm SHARED
    llm_reranker.cc
    llm_reranker_jni.cc
    swipe/futo_swipe_engine.cc
    swipe/futo_swipe_jni.cc
    spelling/english_spell_engine.cc
    spelling/english_spell_jni.cc
)

target_include_directories(myasr_llm PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/src"
    "${ONNX_INC_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/swipe"
    "${CMAKE_CURRENT_SOURCE_DIR}/spelling"
)

target_link_libraries(myasr_llm
    PRIVATE
    llama
    ggml
    onnxruntime
    android
    log
)

target_link_options(myasr_llm
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
