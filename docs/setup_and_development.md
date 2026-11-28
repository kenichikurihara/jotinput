# Setup, Build, and Deployment Guide

This guide walks through configuring the development environment, compiling the APK, and deploying to Android devices or ChromeOS ARCVM.

---

## 🛠️ Environment Prerequisites

- **Host OS**: Linux, macOS, or ChromeOS Linux (Crostini).
- **Java Development Kit (JDK)**: JDK 17 or later (CI uses Temurin JDK 17).
- **Android SDK**:
  - `compileSdk`: 34
  - `minSdk`: 26 (Android 8.0 Oreo)
  - `targetSdk`: 34 (Android 14)
  - `build-tools`: 34.0.0
  - `ndkVersion`: `26.3.11579264`
- **Gradle**: Gradle wrapper ([`gradlew`](../gradlew)).
- **ADB**: Android Debug Bridge (`adb`).

---

## 📦 Step 1: Model Setup

Heavy neural models and large word dictionaries are **never bundled in the APK** (only the compact Mozc 2D bigram connection matrix `app/src/main/assets/mozc/connection_data.bin`, ~14.28 MB, is bundled). The ReazonSpeech ONNX files are placed in `context.getExternalFilesDir(null)/reazonspeech/` on the device in one of two ways:

### Option A: Developer provisioning (used by `deploy.sh`)
Run the download script to fetch the files from Hugging Face into `tools/models/reazonspeech/` (git-ignored):
```bash
./download_model.sh
```
Files:
- `encoder-epoch-99-avg-1.int8.onnx`
- `decoder-epoch-99-avg-1.onnx`
- `joiner-epoch-99-avg-1.onnx`
- `tokens.txt`

`./deploy.sh` then pushes any file missing on each target device to `/sdcard/Android/data/io.github.kenichikurihara.jotinput/files/reazonspeech/`.

### Option B: Download In-App
Skip `download_model.sh`. When the APK is opened without a model, `MainActivity` (**Home** / **Data** tabs) provides in-app HTTPS downloaders for ReazonSpeech, optional NVIDIA Parakeet English ASR, Mozc / SudachiDict / jawiki / place / personal / emoji dictionaries, FUTO Swipe, English Spell, and optional Qwen3.5 GGUF SLM models.

---

### Step 1.2: On-Device SLM Model Setup (Optional for Smart IME Re-ranking)

For context-aware Kana-Kanji candidate re-ranking, quantized **Qwen3.5-0.8B / 2B GGUF** models can be downloaded directly in-app via `SlmModelDownloader` (**Data** tab), or pushed manually from your workstation:

1. **Push to Connected Devices (ARCVM + Phone)**:
   ```bash
   ./tools/push_model_to_device.sh
   ```
   This pushes local GGUF models to `/sdcard/Android/data/io.github.kenichikurihara.jotinput/files/models/` on all connected devices without root requirements.
2. **Graceful Fallback**:
   If no SLM model is installed, `KanaKanjiEngine` runs seamlessly using the standard baseline Mozc Viterbi decoder without any runtime penalty.

---

## 🔨 Step 2: Build the APK

Ensure `local.properties` points to your Android SDK location:
```properties
sdk.dir=/home/<user>/android-sdk
```

Compile the debug APK:
```bash
./gradlew assembleDebug
```

The compiled APK will be created at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Step 3: Deployment

### Dual-Target Deployment Policy (ARCVM + Wi-Fi Connected Phone)

Every modification made to the codebase must be deployed and verified on **both** target environments:
1. **ARCVM (ChromeOS Android Subsystem)**: `arc:5555`
2. **Physical Phone over USB / Wi-Fi Debugging**: e.g., Pixel 10 Pro (`<device-ip>:<port>`)

#### Automated Dual Deployment via `deploy.sh`:
Use the automated deployment script located at [`deploy.sh`](../deploy.sh):
```bash
./deploy.sh
```
This script automatically:
1. Builds the latest debug APK with `./gradlew assembleDebug`.
2. Queries `adb devices` for all active targets.
3. Installs the APK with `install -r`.
4. Grants `android.permission.RECORD_AUDIO`.
5. Enables `io.github.kenichikurihara.jotinput/com.example.myasr.VoiceInputMethodService` as an IME.
6. Launches `MainActivity` on every connected device.

---

### Manual Deployment Steps

#### Deploying to ChromeOS ARCVM

On ChromeOS with Linux development environment (Crostini) and Android app support enabled, the Android subsystem (ARCVM) is available as an ADB target (`arc:5555`).

1. **Verify device connection**:
   ```bash
   adb devices
   ```
   Output:
   ```
   List of devices attached
   arc:5555    device
   ```

2. **Install the APK**:
   ```bash
   adb -s arc:5555 install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Grant Microphone Permission**:
   ```bash
   adb -s arc:5555 shell pm grant io.github.kenichikurihara.jotinput android.permission.RECORD_AUDIO
   ```

4. **Launch the Application**:
   ```bash
   adb -s arc:5555 shell am start -n io.github.kenichikurihara.jotinput/com.example.myasr.MainActivity
   ```

5. **Monitor Logs in Real-Time**:
   ```bash
   adb -s arc:5555 logcat -s AsrManager AudioRecorder MainActivity VoiceInputMethodService
   ```

---

## ⌨️ Step 4: Enabling & Using the Voice Keyboard (IME)

JotInput provides a full-featured Android Input Method Editor (IME) that works system-wide across any application.

### Method 1: Enabling via ADB (Fastest)

1. **Enable the IME service**:
   ```bash
   adb -s arc:5555 shell ime enable io.github.kenichikurihara.jotinput/com.example.myasr.VoiceInputMethodService
   ```

2. **(Optional) Set as the default IME**:
   ```bash
   adb -s arc:5555 shell ime set io.github.kenichikurihara.jotinput/com.example.myasr.VoiceInputMethodService
   ```

3. **Verify enabled IMEs**:
   ```bash
   adb -s arc:5555 shell ime list -s
   ```
   You should see `io.github.kenichikurihara.jotinput/com.example.myasr.VoiceInputMethodService` listed.

### Method 2: Enabling via the App UI

1. Open **JotInput** on your device.
2. In the top (hero) card, tap **音声キーボードを有効にする**: this opens Android's *Manage Keyboards* settings page.
3. Toggle on **JotInput**.
4. Tap **入力方法をJotInputに切り替える** to select JotInput as the active keyboard.
5. Tap the **キーボードテスト** field to try it (the IME status badge shows `IME: 有効` once enabled).

### Using the Keyboard

When any input field is focused (including the **キーボードテスト** field inside `MainActivity` or any external app like Chrome, Notes, or Slack):

1. **Voice Toggle Button** (left of the candidate strip in the keyboard header):
   - Tap **🎙 話す** to start recording speech.
   - Speak naturally. The recording indicator lights while speech is detected (with streaming voice input enabled, text is committed incrementally at sentence boundaries).
   - Tap **完了** when finished (a spinner shows while decoding).
   - The on-device ReazonSpeech Zipformer decodes the audio, applies hotwords and dictionary corrections, then inserts the text at the cursor via `commitText`.
2. **Header Shortcuts**:
   - **Dict**: opens the Custom Dictionary screen to register unfamiliar words.
   - **Clipboard**: shows a preview of the latest clipboard text; tap to paste.
3. **Typing Keys** (flick keyboard with QWERTY / symbols / numpad modes):
   - `⌫` deletes the previous character or selection; `↵` dispatches the editor's primary action (Search, Go, Send, or Enter).
   - Flick/punctuation keys insert `、` `。` etc.; the space key inserts a space or confirms conversion.
   - `🌐` (**Globe**) switches to another keyboard; `📖` opens the Custom Dictionary.
   - Candidates (conversion and next-phrase prediction) appear in the header strip.

---

### Deploying to a Physical Android Phone

1. Enable **Developer Options** and **USB Debugging** on the phone.
2. Connect the phone via USB.
3. Verify connection:
   ```bash
   adb devices
   ```
4. Install and run:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n io.github.kenichikurihara.jotinput/com.example.myasr.MainActivity
   ```

---

## 🔍 Troubleshooting & Common Issues

### 1. `RECORD_AUDIO` Permission Not Prompted
On some ChromeOS container configurations, system permission dialogs may not surface over the active window. You can explicitly grant the permission via ADB:
```bash
adb shell pm grant io.github.kenichikurihara.jotinput android.permission.RECORD_AUDIO
```

### 2. Native Library Architecture Mismatch (`UnsatisfiedLinkError`)
The app ships only 64-bit native libraries (Sherpa-ONNX from [`sherpa-onnx-1.13.8.aar`](../app/libs/sherpa-onnx-1.13.8.aar) plus the llama.cpp-based `libmyasr_llm.so`):
- `x86_64` (ARCVM / ChromeOS, Android Studio Emulators)
- `arm64-v8a` (Modern physical Android devices)

32-bit devices/emulators (`armeabi-v7a`, `x86`) are **not supported**. `app/build.gradle.kts` uses:
```kotlin
ndk {
    abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
}
```

### 3. Model Not Found / Fails to Load
Models are loaded from `getExternalFilesDir(null)/reazonspeech/` (not from APK assets). If `AsrManager` logs `No ReazonSpeech model files found`, run `./download_model.sh` followed by `./deploy.sh`, or use the in-app download card. Verify with:
```bash
adb -s <device> shell ls /sdcard/Android/data/io.github.kenichikurihara.jotinput/files/reazonspeech/
```

---

## 💾 Custom Dictionary Persistence Policy

### Empty by Default Contract
- Fresh installations start with an empty dictionary.
- No dummy entries are seeded automatically, ensuring pure baseline acoustic recognition until the user explicitly registers their terms.

### Data Retention Across App Updates
- The dictionary is persisted in Android's internal sandbox: `context.filesDir/custom_dictionary.json`.
- Standard application updates via `adb install -r` (reinstall keeping data) or signed Release APK upgrades **do not touch** `filesDir`.
- [`DictionaryRepository`](../app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt) checks `file.exists()`. If the file exists, it preserves and loads all entries.
- *Developer Contract*: Future updates must preserve this file. Never call `delete()` on `custom_dictionary.json` during app migrations.

### Inspecting and Backing Up the Dictionary via ADB
To inspect registered dictionary entries directly from the device (Debug builds):
```bash
adb -s arc:5555 shell "run-as io.github.kenichikurihara.jotinput cat files/custom_dictionary.json"
```
To back up the dictionary before testing:
```bash
adb -s arc:5555 exec-out "run-as io.github.kenichikurihara.jotinput cat files/custom_dictionary.json" > custom_dictionary_backup.json
```
To restore the dictionary:
```bash
adb -s arc:5555 push custom_dictionary_backup.json /data/local/tmp/
adb -s arc:5555 shell "run-as io.github.kenichikurihara.jotinput cp /data/local/tmp/custom_dictionary_backup.json files/custom_dictionary.json"
```
