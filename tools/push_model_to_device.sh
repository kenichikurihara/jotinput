#!/usr/bin/env bash
# tools/push_model_to_device.sh
# Pushes Qwen2.5 GGUF models (0.5B and/or 1.5B) to all connected Android targets (ARCVM + Phone)
# using non-root app-specific external files directory and public downloads fallback.

set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_MODEL="${1:-all}"

DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
    echo "Warning: No connected ADB devices found!"
    exit 1
fi

MODELS_TO_PUSH=()
if [ "$TARGET_MODEL" == "0.5b" ]; then
    MODELS_TO_PUSH+=("qwen2.5-0.5b-instruct-q4_k_m.gguf")
elif [ "$TARGET_MODEL" == "1.5b" ]; then
    MODELS_TO_PUSH+=("qwen2.5-1.5b-instruct-q4_k_m.gguf")
elif [ "$TARGET_MODEL" == "0.8b" ] || [ "$TARGET_MODEL" == "3.5-0.8b" ]; then
    MODELS_TO_PUSH+=("qwen3.5-0.8b-instruct-q4_k_m.gguf")
elif [ "$TARGET_MODEL" == "2b" ] || [ "$TARGET_MODEL" == "3.5-2b" ]; then
    MODELS_TO_PUSH+=("qwen3.5-2b-instruct-q4_k_m.gguf")
else
    MODELS_TO_PUSH+=("qwen2.5-0.5b-instruct-q4_k_m.gguf")
    if [ -f "$REPO_DIR/tools/models/qwen2.5-1.5b-instruct-q4_k_m.gguf" ]; then
        MODELS_TO_PUSH+=("qwen2.5-1.5b-instruct-q4_k_m.gguf")
    fi
    if [ -f "$REPO_DIR/tools/models/qwen3.5-0.8b-instruct-q4_k_m.gguf" ]; then
        MODELS_TO_PUSH+=("qwen3.5-0.8b-instruct-q4_k_m.gguf")
    fi
    if [ -f "$REPO_DIR/tools/models/qwen3.5-2b-instruct-q4_k_m.gguf" ]; then
        MODELS_TO_PUSH+=("qwen3.5-2b-instruct-q4_k_m.gguf")
    fi
fi

for DEV in $DEVICES; do
    DEV_MODEL=$(adb -s "$DEV" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    echo "--------------------------------------------------------"
    echo "Pushing SLM model(s) to [$DEV] ($DEV_MODEL)..."

    TARGET_DIR="/sdcard/Android/data/com.example.myasr/files/models"
    adb -s "$DEV" shell "mkdir -p $TARGET_DIR" || true

    for MNAME in "${MODELS_TO_PUSH[@]}"; do
        MPATH="$REPO_DIR/tools/models/$MNAME"
        if [ -f "$MPATH" ]; then
            DEV_FILE=$(adb -s "$DEV" shell "[ -f $TARGET_DIR/$MNAME ] && echo exists" 2>/dev/null | tr -d '\r')
            if [ "$DEV_FILE" != "exists" ]; then
                echo "  -> Pushing $MNAME..."
                adb -s "$DEV" push "$MPATH" "$TARGET_DIR/$MNAME"
            else
                echo "  -> [OK] $MNAME already exists on device, skipping."
            fi
        else
            echo "  -> [Warning] Local file not found: $MPATH"
        fi
    done

    echo "✓ Model provisioning complete for [$DEV]"
done

echo "--------------------------------------------------------"
echo "All devices configured with requested SLM model(s)!"
