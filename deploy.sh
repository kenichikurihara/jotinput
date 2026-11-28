#!/usr/bin/env bash
# deploy.sh - Builds (unless --skip-build) and deploys JotInput in parallel to deduplicated
# connected Android targets (ARCVM + optional USB/Wi-Fi Pixel 10 Pro).

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

if [ -f "$REPO_DIR/.env.local" ]; then
    # Optional local overrides such as JOTINPUT_ADB_WIFI_HOST
    # shellcheck disable=SC1091
    source "$REPO_DIR/.env.local"
fi

APP_ID="io.github.kenichikurihara.jotinput"
APP_NAMESPACE="com.example.myasr"

BUILD_VARIANT="debug"
SKIP_BUILD=false
VERIFY_LOGCAT=false

while [ $# -gt 0 ]; do
    case "$1" in
        --release)
            BUILD_VARIANT="release"
            ;;
        --skip-build)
            SKIP_BUILD=true
            ;;
        --verify)
            VERIFY_LOGCAT=true
            ;;
        -h|--help)
            echo "Usage: ./deploy.sh [--release] [--skip-build] [--verify]"
            exit 0
            ;;
        *)
            echo "Usage: ./deploy.sh [--release] [--skip-build] [--verify]"
            exit 1
            ;;
    esac
    shift
done

if [ "$BUILD_VARIANT" = "release" ]; then
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    if [ "$SKIP_BUILD" = false ]; then
        echo "=== 1. Building Release APK ==="
        ./gradlew assembleRelease
    else
        echo "=== 1. Reusing Existing Release APK (--skip-build) ==="
    fi
else
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    if [ "$SKIP_BUILD" = false ]; then
        echo "=== 1. Building Debug APK ==="
        ./gradlew assembleDebug
    else
        echo "=== 1. Reusing Existing Debug APK (--skip-build) ==="
    fi
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH (omit --skip-build to build it)"
    exit 1
fi
ls -lh "$APK_PATH"

echo ""
echo "=== 2. Discovering & Deduplicating Connected ADB Targets ==="
for OFFLINE_DEV in $(adb devices | awk 'NR>1 && $2=="offline" {print $1}'); do
    adb disconnect "$OFFLINE_DEV" >/dev/null 2>&1 || true
done

if [ -n "${JOTINPUT_ADB_WIFI_HOST:-}" ]; then
    if ! adb devices | awk 'NR>1 && $2=="device" {print $1}' | grep -q "^${JOTINPUT_ADB_WIFI_HOST//./\\.}:"; then
        if ping -c 1 -W 1 "$JOTINPUT_ADB_WIFI_HOST" >/dev/null 2>&1; then
            echo "  -> Scanning active wireless ADB port on $JOTINPUT_ADB_WIFI_HOST..."
            OPEN_PORTS=$(JOTINPUT_ADB_WIFI_HOST="$JOTINPUT_ADB_WIFI_HOST" python3 -c '
import os, socket
from concurrent.futures import ThreadPoolExecutor
ip = os.environ["JOTINPUT_ADB_WIFI_HOST"]
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
                adb connect "$JOTINPUT_ADB_WIFI_HOST:$PORT" >/dev/null 2>&1 || true
            done
            for OFFLINE_DEV in $(adb devices | awk 'NR>1 && $2=="offline" {print $1}'); do
                adb disconnect "$OFFLINE_DEV" >/dev/null 2>&1 || true
            done
        fi
    fi
fi

RAW_DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$RAW_DEVICES" ]; then
    echo "Warning: No connected ADB devices found!"
    exit 1
fi

transport_priority() {
    local dev="$1"
    if [ "$dev" = "arc:5555" ]; then
        echo 2
    elif [[ "$dev" == emulator-* ]]; then
        echo 4
    elif [[ "$dev" == *:* ]]; then
        echo 3
    else
        echo 1
    fi
}

declare -a CANDIDATE_ROWS=()
for DEV in $RAW_DEVICES; do
    ID_RAW=$(adb -s "$DEV" shell 's=$(getprop ro.boot.serialno); [ -z "$s" ] && s=$(getprop ro.serialno); a=$(settings get secure android_id 2>/dev/null); m=$(getprop ro.product.model); printf "%s|%s|%s\n" "$s" "$a" "$m"' 2>/dev/null | tr -d '\r' || true)
    IFS='|' read -r DEV_SERIAL DEV_AID DEV_MODEL <<< "$ID_RAW"
    DEV_MODEL="${DEV_MODEL:-unknown}"
    if [ -n "${DEV_SERIAL}${DEV_AID}" ]; then
        FP="${DEV_SERIAL}:${DEV_AID}"
    else
        FP="handle:${DEV}"
    fi
    PRIO=$(transport_priority "$DEV")
    CANDIDATE_ROWS+=("${PRIO}|${DEV}|${FP}|${DEV_MODEL}")
done

IFS=$'\n' SORTED_ROWS=($(printf '%s\n' "${CANDIDATE_ROWS[@]}" | sort -t'|' -k1,1n -k2,2))
unset IFS

declare -A SEEN_FP=()
declare -A FP_WINNER=()
declare -a TARGET_DEVICES=()
declare -A DEVICE_MODELS=()

for ROW in "${SORTED_ROWS[@]}"; do
    IFS='|' read -r PRIO DEV FP DEV_MODEL <<< "$ROW"
    if [ -n "${SEEN_FP[$FP]+x}" ]; then
        echo "  -> Skipping duplicate ADB handle [$DEV] (same device as [${FP_WINNER[$FP]}], model=${DEV_MODEL})"
        continue
    fi
    SEEN_FP["$FP"]=1
    FP_WINNER["$FP"]="$DEV"
    TARGET_DEVICES+=("$DEV")
    DEVICE_MODELS["$DEV"]="$DEV_MODEL"
done

echo "Unique target devices (${#TARGET_DEVICES[@]}):"
for DEV in "${TARGET_DEVICES[@]}"; do
    echo "  - [$DEV] (${DEVICE_MODELS[$DEV]})"
done
echo ""

# Precompute local asset metadata once before parallel target deployments
SRC_MOZC_BIN=""
LOCAL_BIN_SIZE=0
if [ -f "$REPO_DIR/tools/mozc_dict/system_dictionary.bin" ] && [ "$(wc -c < "$REPO_DIR/tools/mozc_dict/system_dictionary.bin" | tr -d ' ')" -ge 41943040 ]; then
    SRC_MOZC_BIN="$REPO_DIR/tools/mozc_dict/system_dictionary.bin"
elif [ -f "$REPO_DIR/app/src/test/resources/mozc/system_dictionary.bin" ]; then
    SRC_MOZC_BIN="$REPO_DIR/app/src/test/resources/mozc/system_dictionary.bin"
fi
if [ -n "$SRC_MOZC_BIN" ]; then
    LOCAL_BIN_SIZE=$(wc -c < "$SRC_MOZC_BIN" | tr -d ' ')
fi

LOCAL_TSV_SIZE=0
[ -f "$REPO_DIR/tools/mozc_dict/base_dictionary.tsv" ] && LOCAL_TSV_SIZE=$(wc -c < "$REPO_DIR/tools/mozc_dict/base_dictionary.tsv" | tr -d ' ')

LOCAL_CONN_SIZE=0
[ -f "$REPO_DIR/tools/mozc_dict/connection_data.bin" ] && LOCAL_CONN_SIZE=$(wc -c < "$REPO_DIR/tools/mozc_dict/connection_data.bin" | tr -d ' ')

LOCAL_SPELL_VOCAB_SIZE=0
[ -f "$REPO_DIR/tools/models/english_spelling/vocab.txt" ] && LOCAL_SPELL_VOCAB_SIZE=$(wc -c < "$REPO_DIR/tools/models/english_spelling/vocab.txt" | tr -d ' ')

deploy_device() {
    local DEV="$1"
    local DEV_MODEL="$2"
    local START_EPOCH
    START_EPOCH=$(date +%s)

    echo "--------------------------------------------------------"
    echo "Deploying to [$DEV] ($DEV_MODEL)..."

    echo "  -> Installing APK..."
    adb -s "$DEV" install -r "$APK_PATH"

    local BASE="/sdcard/Android/data/$APP_ID/files"
    local PROBE
    PROBE=$(adb -s "$DEV" shell "
      sz() { [ -f \"\$1\" ] && wc -c < \"\$1\" 2>/dev/null || echo 0; }
      ex() { [ -f \"\$1\" ] && echo 1 || echo 0; }
      printf 'reazon=%s\n' \"\$(ex \"$BASE/reazonspeech/tokens.txt\")\"
      printf 'parakeet=%s\n' \"\$([ -f \"$BASE/parakeet-en-110m/model.int8.onnx\" ] && [ -f \"$BASE/parakeet-en-110m/tokens.txt\" ] && echo 1 || echo 0)\"
      printf 'mozc_bin=%s\n' \"\$(sz \"$BASE/mozc/dict_mozc_general.bin\")\"
      printf 'mozc_tsv=%s\n' \"\$(sz \"$BASE/mozc/base_dictionary.tsv\")\"
      printf 'mozc_conn=%s\n' \"\$(sz \"$BASE/mozc/connection_data.bin\")\"
      printf 'swipe=%s\n' \"\$(ex \"$BASE/futo_swipe/encoder.onnx\")\"
      printf 'spell=%s\n' \"\$(ex \"$BASE/english_spelling/gec-encoder-int8.onnx\")\"
      printf 'spell_vocab=%s\n' \"\$(sz \"$BASE/english_spelling/vocab.txt\")\"
      rm -f \"$BASE/mozc/system_dictionary.bin\" 2>/dev/null || true
    " 2>/dev/null | tr -d '\r ')

    probe_val() {
        printf '%s\n' "$PROBE" | awk -F= -v k="$1" '$1==k {print $2; exit}' | tr -cd '0-9'
    }

    local DEV_REAZON DEV_PARAKEET REMOTE_BIN_SIZE REMOTE_TSV_SIZE REMOTE_CONN_SIZE DEV_SWIPE DEV_SPELL REMOTE_SPELL_VOCAB_SIZE
    DEV_REAZON=$(probe_val reazon); DEV_REAZON="${DEV_REAZON:-0}"
    DEV_PARAKEET=$(probe_val parakeet); DEV_PARAKEET="${DEV_PARAKEET:-0}"
    REMOTE_BIN_SIZE=$(probe_val mozc_bin); REMOTE_BIN_SIZE="${REMOTE_BIN_SIZE:-0}"
    REMOTE_TSV_SIZE=$(probe_val mozc_tsv); REMOTE_TSV_SIZE="${REMOTE_TSV_SIZE:-0}"
    REMOTE_CONN_SIZE=$(probe_val mozc_conn); REMOTE_CONN_SIZE="${REMOTE_CONN_SIZE:-0}"
    DEV_SWIPE=$(probe_val swipe); DEV_SWIPE="${DEV_SWIPE:-0}"
    DEV_SPELL=$(probe_val spell); DEV_SPELL="${DEV_SPELL:-0}"
    REMOTE_SPELL_VOCAB_SIZE=$(probe_val spell_vocab); REMOTE_SPELL_VOCAB_SIZE="${REMOTE_SPELL_VOCAB_SIZE:-0}"

    if [ "$DEV_REAZON" != "1" ] && [ -d "$REPO_DIR/tools/models/reazonspeech" ]; then
        echo "  -> Provisioning ReazonSpeech model to storage..."
        adb -s "$DEV" shell "mkdir -p $BASE/reazonspeech && chmod 2775 $BASE/reazonspeech 2>/dev/null" || true
        for MFILE in tokens.txt decoder-epoch-99-avg-1.onnx joiner-epoch-99-avg-1.onnx encoder-epoch-99-avg-1.int8.onnx; do
            if [ -f "$REPO_DIR/tools/models/reazonspeech/$MFILE" ]; then
                adb -s "$DEV" push "$REPO_DIR/tools/models/reazonspeech/$MFILE" "$BASE/reazonspeech/$MFILE"
            fi
        done
    fi

    if [ "$DEV_PARAKEET" != "1" ] && [ -d "$REPO_DIR/tools/models/parakeet-en-110m" ]; then
        echo "  -> Provisioning English Parakeet ASR model to storage..."
        adb -s "$DEV" shell "mkdir -p $BASE/parakeet-en-110m && chmod 2775 $BASE/parakeet-en-110m 2>/dev/null" || true
        for ENFILE in model.int8.onnx tokens.txt; do
            if [ -f "$REPO_DIR/tools/models/parakeet-en-110m/$ENFILE" ]; then
                adb -s "$DEV" push "$REPO_DIR/tools/models/parakeet-en-110m/$ENFILE" "$BASE/parakeet-en-110m/$ENFILE"
            fi
        done
    fi

    if [ -n "$SRC_MOZC_BIN" ] && [ "$REMOTE_BIN_SIZE" -lt "$LOCAL_BIN_SIZE" ]; then
        echo "  -> Updating Mozc dict_mozc_general.bin in device storage ($LOCAL_BIN_SIZE bytes)..."
        adb -s "$DEV" shell "mkdir -p $BASE/mozc && chmod 2775 $BASE/mozc 2>/dev/null" || true
        adb -s "$DEV" push "$SRC_MOZC_BIN" "$BASE/mozc/dict_mozc_general.bin"
    fi

    if [ "$LOCAL_TSV_SIZE" -gt 0 ] && [ "$LOCAL_TSV_SIZE" != "$REMOTE_TSV_SIZE" ]; then
        echo "  -> Updating Mozc base dictionary in device storage ($LOCAL_TSV_SIZE bytes)..."
        adb -s "$DEV" shell "mkdir -p $BASE/mozc && chmod 2775 $BASE/mozc 2>/dev/null" || true
        adb -s "$DEV" push "$REPO_DIR/tools/mozc_dict/base_dictionary.tsv" "$BASE/mozc/base_dictionary.tsv"
    fi

    if [ "$LOCAL_CONN_SIZE" -gt 0 ] && [ "$LOCAL_CONN_SIZE" != "$REMOTE_CONN_SIZE" ]; then
        echo "  -> Updating Mozc connection matrix in device storage ($LOCAL_CONN_SIZE bytes)..."
        adb -s "$DEV" shell "mkdir -p $BASE/mozc && chmod 2775 $BASE/mozc 2>/dev/null" || true
        adb -s "$DEV" push "$REPO_DIR/tools/mozc_dict/connection_data.bin" "$BASE/mozc/connection_data.bin"
    fi

    if [ "$DEV_SWIPE" != "1" ] && [ -d "$REPO_DIR/tools/models/futo_swipe" ]; then
        echo "  -> Provisioning FUTO Swipe models to storage..."
        adb -s "$DEV" shell "mkdir -p $BASE/futo_swipe && chmod 2775 $BASE/futo_swipe 2>/dev/null" || true
        for SFILE in encoder.onnx decoder.onnx context_lm.onnx embeddings.onnx vocab.txt; do
            if [ -f "$REPO_DIR/tools/models/futo_swipe/$SFILE" ]; then
                adb -s "$DEV" push "$REPO_DIR/tools/models/futo_swipe/$SFILE" "$BASE/futo_swipe/$SFILE"
            fi
        done
    fi

    if [ -d "$REPO_DIR/tools/models/english_spelling" ]; then
        if [ "$DEV_SPELL" != "1" ]; then
            echo "  -> Provisioning English Spelling Correction models to storage..."
            adb -s "$DEV" shell "mkdir -p $BASE/english_spelling && chmod 2775 $BASE/english_spelling 2>/dev/null" || true
            for SPFILE in gec-encoder-int8.onnx gec-decoder-int8.onnx spm_unigram.bin; do
                if [ -f "$REPO_DIR/tools/models/english_spelling/$SPFILE" ]; then
                    adb -s "$DEV" push "$REPO_DIR/tools/models/english_spelling/$SPFILE" "$BASE/english_spelling/$SPFILE"
                fi
            done
        fi
        if [ "$LOCAL_SPELL_VOCAB_SIZE" -gt 0 ] && [ "$LOCAL_SPELL_VOCAB_SIZE" != "$REMOTE_SPELL_VOCAB_SIZE" ]; then
            echo "  -> Updating English spelling vocab.txt in device storage ($LOCAL_SPELL_VOCAB_SIZE bytes)..."
            adb -s "$DEV" shell "mkdir -p $BASE/english_spelling && chmod 2775 $BASE/english_spelling 2>/dev/null" || true
            adb -s "$DEV" push "$REPO_DIR/tools/models/english_spelling/vocab.txt" "$BASE/english_spelling/vocab.txt"
        fi
    fi

    if [ "$VERIFY_LOGCAT" = true ]; then
        adb -s "$DEV" logcat -c >/dev/null 2>&1 || true
    fi

    echo "  -> Granting RECORD_AUDIO, enabling IME, and launching MainActivity..."
    adb -s "$DEV" shell "
      pm grant $APP_ID android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
      ime enable $APP_ID/$APP_NAMESPACE.VoiceInputMethodService >/dev/null 2>&1 || true
      am force-stop $APP_ID >/dev/null 2>&1 || true
      am start -W -n $APP_ID/$APP_NAMESPACE.MainActivity
    "

    if [ "$VERIFY_LOGCAT" = true ]; then
        local VERIFIED_ASR=false
        local VERIFIED_APP=false
        local LOG_SNAPSHOT=""
        for _ in $(seq 1 45); do
            LOG_SNAPSHOT=$(adb -s "$DEV" logcat -d -s AsrApp:I AsrManager:I ActivityTaskManager:I AndroidRuntime:E 2>/dev/null || true)
            if printf '%s\n' "$LOG_SNAPSHOT" | grep -q "FATAL EXCEPTION" && printf '%s\n' "$LOG_SNAPSHOT" | grep -q "Process: $APP_ID"; then
                echo "  ✖ Fatal runtime crash detected during startup:"
                printf '%s\n' "$LOG_SNAPSHOT" | grep -A 25 "FATAL EXCEPTION"
                return 1
            fi
            if printf '%s\n' "$LOG_SNAPSHOT" | grep -q "ReazonSpeech model successfully loaded"; then
                VERIFIED_ASR=true
            fi
            if printf '%s\n' "$LOG_SNAPSHOT" | grep -q "AsrApp initialized"; then
                VERIFIED_APP=true
            fi
            if [ "$VERIFIED_ASR" = true ] && [ "$VERIFIED_APP" = true ]; then
                break
            fi
            sleep 1
        done
        if [ "$VERIFIED_ASR" = true ]; then
            echo "  ✓ Logcat verified: AsrApp initialized=$VERIFIED_APP, ReazonSpeech loaded=true"
        else
            echo "  ⚠ Logcat verification timed out waiting for ReazonSpeech load (AsrApp=$VERIFIED_APP)"
            printf '%s\n' "$LOG_SNAPSHOT" | tail -n 15
            return 1
        fi
    fi

    local END_EPOCH ELAPSED
    END_EPOCH=$(date +%s)
    ELAPSED=$((END_EPOCH - START_EPOCH))
    echo "  ✓ Device [$DEV] ($DEV_MODEL) deployed in ${ELAPSED}s."
}

TMP_LOG_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_LOG_DIR"' EXIT

declare -a PIDS=()
declare -a LOG_FILES=()

for i in "${!TARGET_DEVICES[@]}"; do
    DEV="${TARGET_DEVICES[$i]}"
    LOG_FILE="$TMP_LOG_DIR/deploy_${i}.log"
    LOG_FILES+=("$LOG_FILE")
    deploy_device "$DEV" "${DEVICE_MODELS[$DEV]}" >"$LOG_FILE" 2>&1 &
    PIDS+=($!)
done

FAILURES=0
for i in "${!PIDS[@]}"; do
    if ! wait "${PIDS[$i]}"; then
        FAILURES=$((FAILURES + 1))
    fi
    cat "${LOG_FILES[$i]}"
done

echo "--------------------------------------------------------"
if [ "$FAILURES" -gt 0 ]; then
    echo "Error: $FAILURES target deployment(s) failed."
    exit 1
fi
echo "=== All ${#TARGET_DEVICES[@]} unique target(s) deployed successfully! ==="
