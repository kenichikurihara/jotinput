# Custom Dictionary Implementation Plan

This document outlines the concrete implementation plan for integrating the custom dictionary feature with the `(Word, Reading)` dual-key specification into JotInput.

---

## 🎯 Objectives

1. Allow users to register custom Japanese vocabulary pairs: **`Word (表記)`** and **`Reading (よみ)`**.
2. Seamlessly feed registered pairs into **Sherpa-ONNX in-beam contextual biasing** (`modified_beam_search` with dynamic `hotwords.txt`).
3. Apply a fast, deterministic **post-processing filter** to replace raw Kana readings and homophones with the canonical surface form.
4. Provide an intuitive, modern **Dictionary Management UI** on Android to add, toggle, and delete words with instant hotword synchronization.

---

## 🏗️ Architecture & Component Design

```
+------------------------------------------------------------------------------------+
|                                    Dictionary UI                                   |
|                (DictionaryActivity with RecyclerView & Add-Word Modal)             |
+------------------------------------------------------------------------------------+
                                          |
                                          v
+------------------------------------------------------------------------------------+
|                            DictionaryRepository                                    |
|              - Persists List<DictionaryEntry> in local JSON storage                |
|              - Auto-populates recommended default entries (ReazonSpeech, etc.)     |
|              - Notifies listeners on dictionary updates                            |
+------------------------------------------------------------------------------------+
                                          |
                   +----------------------+----------------------+
                   |                                             |
                   v                                             v
+--------------------------------------+     +---------------------------------------+
|        HotwordsGenerator             |     |       DictionaryPostProcessor         |
| - Expands (word, reading) into:      |     | - Scans decoded ASR text for readings |
|   - Kanji/Surface: "栗原 :3.5"       |     |   ("くりはら" or "クリハラ")          |
|   - Hiragana: "くりはら :3.5"        |     | - Deterministically swaps to "栗原"   |
|   - Katakana: "クリハラ :3.5"        |     | - Normalizes case for Latin terms     |
| - Writes to hotwords.txt on disk     |     |   (e.g., "reazonspeech" ->            |
+--------------------------------------+     |    "ReazonSpeech")                    |
                   |                         +---------------------------------------+
                   v                                             |
+--------------------------------------+                         |
|             AsrManager               |                         |
| - Configured with:                   |                         |
|   decodingMethod="modified_beam_search"|                       |
|   hotwordsFile=hotwords.txt          |                         |
|   modelingUnit="cjkchar"             |                         |
+--------------------------------------+                         |
                   |                                             |
                   +---------------------> Raw Text              |
                                              |                  |
                                              v                  v
                                        [Apply PostProcessor Filter]
                                                     |
                                                     v
                                          Final Respected Output
```

---

## 📋 Step-by-Step Execution Plan

### Step 1: Data Model & Kana Utilities
- Create [`DictionaryEntry.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/DictionaryEntry.kt):
  - `id: String`
  - `word: String` (表記)
  - `reading: String` (よみ in Hiragana)
  - `priority: Priority` (`LOW = 1.5f`, `NORMAL = 3.0f`, `HIGH = 4.5f`)
  - `isEnabled: Boolean`
- Create [`JapaneseKanaUtils.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/JapaneseKanaUtils.kt):
  - `katakanaToHiragana(text)`: Converts Katakana characters (`\u30A1`..`\u30F6`) to Hiragana (`\u3041`..`\u3096`).
  - `hiraganaToKatakana(text)`: Converts Hiragana to Katakana.
  - `isValidKana(text)`: Checks if a reading string contains only valid Hiragana, Katakana, and chōonpu (`ー`).
  - `normalizeReading(text)`: Cleans whitespace and standardizes reading to Hiragana.

### Step 2: Storage & Repository
- Create [`DictionaryRepository.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/DictionaryRepository.kt):
  - Stores entries in `context.filesDir/custom_dictionary.json` for fast, lightweight atomic I/O.
  - Includes default starter terms:
    - `ReazonSpeech` / `れいぞんすぴーち` (High priority)
    - `栗原` / `くりはら` (Normal priority)
    - `貴社` / `きしゃ` (Normal priority)
    - `DX` / `でぃーえっくす` (Normal priority)
    - `音声認識` / `おんせいにんしき` (Normal priority)
  - Provides `getEntries()`, `addEntry()`, `updateEntry()`, `deleteEntry()`, and `toggleEntry()`.

### Step 3: Hotwords Generator & In-Beam Biasing
- Create [`HotwordsGenerator.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/HotwordsGenerator.kt):
  - Reads active dictionary entries.
  - Generates entries in `cjkchar` format:
    ```text
    栗原 :3.0
    くりはら :3.0
    クリハラ :3.0
    ReazonSpeech :4.5
    れいぞんすぴーち :4.5
    レイゾンスピーチ :4.5
    ```
  - Writes to `context.filesDir/hotwords.txt`.
- Update [`AsrManager.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/AsrManager.kt):
  - Set `modelConfig.modelingUnit = "cjkchar"`.
  - When a hotwords file exists and has entries, set `decodingMethod = "modified_beam_search"`, `hotwordsFile = path`, `hotwordsScore = 2.5f`.
  - Otherwise, fallback cleanly to `"greedy_search"`.
  - Expose `reloadHotwords()` to update the active recognizer session when the user modifies their dictionary.

### Step 4: Phonetic & Surface Post-Processor
- Create [`DictionaryPostProcessor.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/DictionaryPostProcessor.kt):
  - Executes directly on the decoded string from `recognizer.decode()`.
  - Replaces Hiragana reading matches and Katakana reading matches with the canonical `word`.
  - Performs case-insensitive matching for Latin/Alphanumeric words (`reazonspeech` $\rightarrow$ `ReazonSpeech`).

### Step 5: Android Dictionary UI
- Create `res/layout/activity_dictionary.xml`:
  - Header toolbar with back arrow and title "Custom Dictionary (単語登録)".
  - Top "Add Word" card with:
    - Input: `単語 (Word / 表記)`
    - Input: `よみ (Reading / ひらがな)`
    - Priority selector (Normal / High)
    - "Add (追加)" button
  - Summary chip with count of active dictionary entries.
  - `RecyclerView` showing registered items:
    - Primary surface text (`word`)
    - Subtitle with reading (`よみ: くりはら / クリハラ`)
    - Priority badge
    - Switch to enable/disable
    - Delete button (trash icon)
- Create [`DictionaryActivity.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/DictionaryActivity.kt) and [`DictionaryAdapter.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/DictionaryAdapter.kt).
- Update [`MainActivity.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/MainActivity.kt) and [`activity_main.xml`](file:///home/kenichikurihara/repos/myasr/app/src/main/res/layout/activity_main.xml):
  - Add a "Dictionary (単語登録)" button in the UI.
  - When returning from `DictionaryActivity`, reload hotwords and notify `AsrManager`.

### Step 6: Verification & Documentation
- Build and verify zero compilation errors (`./gradlew assembleDebug`).
- Deploy to ARCVM (`arc:5555`) via ADB.
- Test adding entries via UI and verify logcat hotword loading and transcription filtering.
- Update `docs/architecture.md`, `docs/index.md`, and `README.md`.
