#!/usr/bin/env bash
set -e

MODEL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tools/models/reazonspeech"
mkdir -p "$MODEL_DIR"

BASE_URL="https://huggingface.co/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main"

FILES=(
  "tokens.txt"
  "decoder-epoch-99-avg-1.onnx"
  "joiner-epoch-99-avg-1.onnx"
  "encoder-epoch-99-avg-1.int8.onnx"
)

echo "Downloading ReazonSpeech quantized model to $MODEL_DIR..."

for FILE in "${FILES[@]}"; do
  DEST="$MODEL_DIR/$FILE"
  if [ -f "$DEST" ] && [ -s "$DEST" ]; then
    echo "  [OK] $FILE already exists, skipping."
  else
    echo "  -> Downloading $FILE..."
    curl -L --fail --retry 3 --progress-bar -o "$DEST" "$BASE_URL/$FILE"
  fi
done

echo ""
echo "All model files are downloaded in $MODEL_DIR:"
ls -lh "$MODEL_DIR"
echo ""
echo "You can now rebuild the APK with bundled assets: ./gradlew assembleDebug"
