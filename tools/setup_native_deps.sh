#!/bin/bash
# Sets up native build dependencies (llama.cpp source and onnxruntime prebuilts)
set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== Setting up native build dependencies ==="

# 1. llama.cpp source
if [ ! -d "tools/llama_cpp_src" ]; then
    echo "Cloning llama.cpp..."
    git clone --depth 1 https://github.com/ggml-org/llama.cpp.git tools/llama_cpp_src
fi

# 2. Extract onnxruntime from bundled sherpa-onnx AAR
mkdir -p tools/models/futo_swipe/lib/arm64-v8a tools/models/futo_swipe/lib/x86_64
if [ ! -f "tools/models/futo_swipe/lib/arm64-v8a/libonnxruntime.so" ]; then
    echo "Extracting arm64-v8a libonnxruntime.so from sherpa-onnx.aar..."
    unzip -p app/libs/sherpa-onnx-1.13.8.aar jni/arm64-v8a/libonnxruntime.so > tools/models/futo_swipe/lib/arm64-v8a/libonnxruntime.so
fi
if [ ! -f "tools/models/futo_swipe/lib/x86_64/libonnxruntime.so" ]; then
    echo "Extracting x86_64 libonnxruntime.so from sherpa-onnx.aar..."
    unzip -p app/libs/sherpa-onnx-1.13.8.aar jni/x86_64/libonnxruntime.so > tools/models/futo_swipe/lib/x86_64/libonnxruntime.so
fi

echo "=== Native dependencies ready ==="
