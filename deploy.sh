#!/usr/bin/env bash
# deploy.sh - Automatically builds and installs MyASR to all connected Android targets
# (ARCVM + Wi-Fi debugging phone).

set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

echo "=== 1. Building Debug APK ==="
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

echo ""
echo "=== 2. Discovering Connected ADB Targets ==="
# Prune stale offline wireless connections and auto-discover Pixel 10 Pro if not connected
for OFFLINE_DEV in $(adb devices | awk 'NR>1 && $2=="offline" {print $1}'); do
    adb disconnect "$OFFLINE_DEV" >/dev/null 2>&1 || true
done

if ! adb devices | awk 'NR>1 && $2=="device" {print $1}' | grep -q "^192\.168\.86\.46:"; then
    if ping -c 1 -W 1 <device-ip> >/dev/null 2>&1; then
        echo "  -> Scanning active wireless ADB port on <device-ip>..."
        OPEN_PORTS=$(python3 -c '
import socket
from concurrent.futures import ThreadPoolExecutor
ip = "<device-ip>"
def check(p):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.12)
    try:
        return p if s.connect_ex((ip, p)) == 0 else None
    except Exception:
        return None
    finally:
        s.close()
with ThreadPoolExecutor(max_workers=128) as ex:
    for p in ex.map(check, range(30000, 50000)):
        if p: print(p)
' 2>/dev/null || true)
        for PORT in $OPEN_PORTS; do
            adb connect "<device-ip>:$PORT" >/dev/null 2>&1 || true
        done
        for OFFLINE_DEV in $(adb devices | awk 'NR>1 && $2=="offline" {print $1}'); do
            adb disconnect "$OFFLINE_DEV" >/dev/null 2>&1 || true
        done
    fi
fi

# Get list of device IDs (excluding header line and offline devices)
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')

if [ -z "$DEVICES" ]; then
    echo "Warning: No connected ADB devices found!"
    exit 1
fi

echo "Found target devices:"
echo "$DEVICES"
echo ""

for DEV in $DEVICES; do
    echo "--------------------------------------------------------"
    DEV_MODEL=$(adb -s "$DEV" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    echo "Deploying to [$DEV] ($DEV_MODEL)..."
    
    echo "  -> Installing APK..."
    adb -s "$DEV" install -r "$APK_PATH"
    
    # Ensure ReazonSpeech model is present in device external storage
    DEV_TOKENS=$(adb -s "$DEV" shell "[ -f /sdcard/Android/data/com.example.myasr/files/reazonspeech/tokens.txt ] && echo exists" 2>/dev/null | tr -d '\r')
    if [ "$DEV_TOKENS" != "exists" ]; then
        if [ -d "$REPO_DIR/tools/models/reazonspeech" ]; then
            echo "  -> Provisioning ReazonSpeech model to storage..."
            adb -s "$DEV" shell "mkdir -p /sdcard/Android/data/com.example.myasr/files/reazonspeech" || true
            for MFILE in tokens.txt decoder-epoch-99-avg-1.onnx joiner-epoch-99-avg-1.onnx encoder-epoch-99-avg-1.int8.onnx; do
                if [ -f "$REPO_DIR/tools/models/reazonspeech/$MFILE" ]; then
                    adb -s "$DEV" push "$REPO_DIR/tools/models/reazonspeech/$MFILE" "/sdcard/Android/data/com.example.myasr/files/reazonspeech/$MFILE"
                fi
            done
        fi
    fi

    # Ensure Mozc memory-mapped binary dictionary is up-to-date in device storage
    if [ -f "$REPO_DIR/tools/mozc_dict/system_dictionary.bin" ]; then
        LOCAL_BIN_SIZE=$(wc -c < "$REPO_DIR/tools/mozc_dict/system_dictionary.bin" | tr -d ' ')
        REMOTE_BIN_SIZE=$(adb -s "$DEV" shell "wc -c < /sdcard/Android/data/com.example.myasr/files/mozc/system_dictionary.bin 2>/dev/null" | tr -d '\r ')
        if [ "$LOCAL_BIN_SIZE" != "$REMOTE_BIN_SIZE" ]; then
            echo "  -> Updating Mozc system_dictionary.bin in device storage ($LOCAL_BIN_SIZE bytes)..."
            adb -s "$DEV" shell "mkdir -p /sdcard/Android/data/com.example.myasr/files/mozc" || true
            adb -s "$DEV" push "$REPO_DIR/tools/mozc_dict/system_dictionary.bin" "/sdcard/Android/data/com.example.myasr/files/mozc/system_dictionary.bin"
        fi
    fi

    # Ensure Mozc base dictionary is up-to-date in device storage
    if [ -f "$REPO_DIR/tools/mozc_dict/base_dictionary.tsv" ]; then
        LOCAL_SIZE=$(wc -c < "$REPO_DIR/tools/mozc_dict/base_dictionary.tsv" | tr -d ' ')
        REMOTE_SIZE=$(adb -s "$DEV" shell "wc -c < /sdcard/Android/data/com.example.myasr/files/mozc/base_dictionary.tsv 2>/dev/null" | tr -d '\r ')
        if [ "$LOCAL_SIZE" != "$REMOTE_SIZE" ]; then
            echo "  -> Updating Mozc base dictionary in device storage ($LOCAL_SIZE bytes)..."
            adb -s "$DEV" shell "mkdir -p /sdcard/Android/data/com.example.myasr/files/mozc" || true
            adb -s "$DEV" push "$REPO_DIR/tools/mozc_dict/base_dictionary.tsv" "/sdcard/Android/data/com.example.myasr/files/mozc/base_dictionary.tsv"
        fi
    fi

    # Ensure Mozc connection matrix is up-to-date in device storage
    if [ -f "$REPO_DIR/tools/mozc_dict/connection_data.bin" ]; then
        LOCAL_CONN_SIZE=$(wc -c < "$REPO_DIR/tools/mozc_dict/connection_data.bin" | tr -d ' ')
        REMOTE_CONN_SIZE=$(adb -s "$DEV" shell "wc -c < /sdcard/Android/data/com.example.myasr/files/mozc/connection_data.bin 2>/dev/null" | tr -d '\r ')
        if [ "$LOCAL_CONN_SIZE" != "$REMOTE_CONN_SIZE" ]; then
            echo "  -> Updating Mozc connection matrix in device storage ($LOCAL_CONN_SIZE bytes)..."
            adb -s "$DEV" shell "mkdir -p /sdcard/Android/data/com.example.myasr/files/mozc" || true
            adb -s "$DEV" push "$REPO_DIR/tools/mozc_dict/connection_data.bin" "/sdcard/Android/data/com.example.myasr/files/mozc/connection_data.bin"
        fi
    fi

    # Ensure FUTO Swipe models and vocabulary are up-to-date in device storage
    if [ -d "$REPO_DIR/tools/models/futo_swipe" ]; then
        DEV_SWIPE_ENC=$(adb -s "$DEV" shell "[ -f /sdcard/Android/data/com.example.myasr/files/futo_swipe/encoder.onnx ] && echo exists" 2>/dev/null | tr -d '\r')
        if [ "$DEV_SWIPE_ENC" != "exists" ]; then
            echo "  -> Provisioning FUTO Swipe models to storage..."
            adb -s "$DEV" shell "mkdir -p /sdcard/Android/data/com.example.myasr/files/futo_swipe" || true
            for SFILE in encoder.onnx decoder.onnx context_lm.onnx embeddings.onnx vocab.txt; do
                if [ -f "$REPO_DIR/tools/models/futo_swipe/$SFILE" ]; then
                    adb -s "$DEV" push "$REPO_DIR/tools/models/futo_swipe/$SFILE" "/sdcard/Android/data/com.example.myasr/files/futo_swipe/$SFILE"
                fi
            done
        fi
    fi

    # Ensure English Spelling Correction models are up-to-date in device storage
    if [ -d "$REPO_DIR/tools/models/english_spelling" ]; then
        DEV_SPELL_ENC=$(adb -s "$DEV" shell "[ -f /sdcard/Android/data/com.example.myasr/files/english_spelling/gec-encoder-int8.onnx ] && echo exists" 2>/dev/null | tr -d '\r')
        if [ "$DEV_SPELL_ENC" != "exists" ]; then
            echo "  -> Provisioning English Spelling Correction models to storage..."
            adb -s "$DEV" shell "mkdir -p /sdcard/Android/data/com.example.myasr/files/english_spelling" || true
            for SPFILE in gec-encoder-int8.onnx gec-decoder-int8.onnx spm_unigram.bin vocab.txt; do
                if [ -f "$REPO_DIR/tools/models/english_spelling/$SPFILE" ]; then
                    adb -s "$DEV" push "$REPO_DIR/tools/models/english_spelling/$SPFILE" "/sdcard/Android/data/com.example.myasr/files/english_spelling/$SPFILE"
                fi
            done
        fi
    fi

    echo "  -> Granting RECORD_AUDIO permission..."
    adb -s "$DEV" shell pm grant com.example.myasr android.permission.RECORD_AUDIO || true
    
    echo "  -> Enabling Voice Keyboard (IME)..."
    adb -s "$DEV" shell ime enable com.example.myasr/.VoiceInputMethodService || true
    
    echo "  -> Launching MainActivity..."
    adb -s "$DEV" shell am force-stop com.example.myasr || true
    adb -s "$DEV" shell am start -n com.example.myasr/.MainActivity || true
    
    echo "  ✓ Device [$DEV] ($DEV_MODEL) successfully deployed and configured."
done

echo "--------------------------------------------------------"
echo "=== All targets deployed successfully! ==="
