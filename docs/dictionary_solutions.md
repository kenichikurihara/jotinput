# Custom Dictionary Solutions for Japanese On-Device ASR
## Dual-Key Specification: `(Word, Reading)` / `(表記, よみ)`

This document details the architectural design and implementation plan for enabling users to register custom terms into JotInput using **explicit pairs of both a Word and its Reading** (`Word, Reading`).

---

## 🎯 1. Core Principle: The `(Word, Reading)` Contract

Every custom dictionary entry is strictly structured as an explicit pair:

$$\text{Dictionary Entry} = \big(\text{Word (表記)}, \text{Reading (よみ)}\big)$$

### Concrete Examples:
| Category | Word (表記 / Surface) | Reading (よみ / Pronunciation) | Derived Variants (Auto-Generated) |
| :--- | :--- | :--- | :--- |
| **Personal Name** | `中村` | `なかむら` | `ナカムラ` |
| **Company / Project** | `ReazonSpeech` | `れいぞんすぴーち` | `レイゾンスピーチ`, `リーズンスピーチ` |
| **Business Homophone** | `貴社` | `きしゃ` | `キシャ`, `きしゃ` |
| **Technical Acronym** | `DX` | `でぃーえっくす` | `ディーエックス` |
| **Special Notation** | `打ち合わせ` | `うちあわせ` | `ウチアワセ`, `打合せ` |

### Why Requiring Both Word & Reading Unlocks High Accuracy:
1. **Acoustic Reality vs. Orthographic Reality**:
   - The microphone hears sounds (**Morae / Reading**).
   - The user wants specific text (**Kanji / English / Surface**).
   - Knowing the exact reading allows the system to bridge the acoustic representation directly to the target surface without guessing phonetic pronunciation.
2. **Dual-Channel Reinforcement**:
   - **Channel 1 (Acoustic Biasing)**: Biases both the Kanji and the Kana during transducer beam search.
   - **Channel 2 (Phonetic Substitution)**: Immediately converts any acoustic Kana fallback into the user's Kanji or English spelling.
3. **Automatic Katakana / Hiragana Expansion**:
   - Because the reading is explicitly known in Kana, the system automatically creates both Hiragana (`なかむら`) and Katakana (`ナカムラ`) variants for free with zero extra user input.

---

## 🏗️ 2. Architectural Pipeline with `(Word, Reading)`

```
+----------------------------------------------------------------------------------------------------+
|                                    User Dictionary Entry                                           |
|                           Word: "中村"   |   Reading: "なかむら"                                   |
+----------------------------------------------------------------------------------------------------+
                                                  |
                         +------------------------+------------------------+
                         |                                                 |
                         v                                                 v
           [Auto-Expansion Generator]                         [Reading-to-Surface Map]
       Generates: "中村", "なかむら", "ナカムラ"             "なかむら" -> "中村", "ナカムラ" -> "中村"
                         |                                                 |
                         v                                                 |
+----------------------------------------------------+                     |
| Stage 1: In-Beam Contextual Biasing                |                     |
| - Sherpa-ONNX modified_beam_search                 |                     |
| - Hotwords file contains both Word & Readings      |                     |
|   "中村 :3.5", "なかむら :3.5", "ナカムラ :3.5"     |                     |
+----------------------------------------------------+                     |
                         | Raw ASR Token Output                            |
                         v                                                 |
+----------------------------------------------------+                     |
| Stage 2: Phonetic & Homophone Replacement Filter   |<--------------------+
| - Scans ASR text for phonetic reading matches      |
| - Converts "なかむら" or "ナカムラ" -> "中村"       |
| - Resolves registered homophones (e.g. 記者 -> 貴社)|
+----------------------------------------------------+
                         |
                         v
+----------------------------------------------------+
| Stage 3: Surface Normalizer                        |
| - Enforces exact capitalization & spacing          |
|   (e.g., "reazonspeech" -> "ReazonSpeech")         |
+----------------------------------------------------+
                         |
                         v
             Final Respected Transcription
```

---

## ⚙️ 3. Detailed Stage Breakdown

### Stage 1: In-Beam Contextual Biasing (Sherpa-ONNX Hotwords)

In ReazonSpeech, the modeling unit is character-level (`modelingUnit = "cjkchar"`). When the user provides `(Word, Reading)`, the hotwords file is populated with:

```text
中村 :3.5
なかむら :3.5
ナカムラ :3.5
ReazonSpeech :4.0
れいぞんすぴーち :4.0
レイゾンスピーチ :4.0
貴社 :4.5
きしゃ :4.5
キシャ :4.5
```

#### Why register all forms?
- If the acoustic encoder has strong acoustic confidence for the Kanji tokens, `中村 :3.5` provides the needed boost to beat general words.
- If the acoustic encoder is uncertain of the Kanji and begins emitting phonetic Hiragana tokens (`な` $\rightarrow$ `か` $\rightarrow$ `む` $\rightarrow$ `ら`), `なかむら :3.5` keeps the beam alive and reinforces the sequence!

#### Native Engine Configuration in [`AsrManager.kt`](app/src/main/java/com/example/myasr/AsrManager.kt):
```kotlin
val modelConfig = OfflineModelConfig(
    transducer = OfflineTransducerModelConfig(
        encoder = encoderPath,
        decoder = decoderPath,
        joiner = joinerPath
    ),
    tokens = tokensPath,
    modelType = "transducer",
    modelingUnit = "cjkchar",
    numThreads = 2,
    provider = "cpu"
)

val config = OfflineRecognizerConfig(
    modelConfig = modelConfig,
    decodingMethod = "modified_beam_search",
    maxActivePaths = 4,
    hotwordsFile = hotwordsFile.absolutePath,
    hotwordsScore = 2.5f
)
```

---

### Stage 2: Phonetic & Homophone Replacement Filter

Even with in-beam biasing, ASR models may occasionally output the raw reading in Kana (e.g. `なかむらさん`) or a common homophone (e.g. `明日は記者に伺います` instead of `貴社`).

Because the system holds the explicit pair `(Word: "貴社", Reading: "きしゃ")`:
1. **Direct Kana Substitution**: Any occurrence of `きしゃ` or `キシャ` is immediately replaced with `貴社`.
2. **Homophone Disambiguation**:
   - The user can flag homophone words or register known competing homophones (`記者`, `汽車`, `帰社`).
   - When the target reading is matched against the candidate span, the user's preferred domain Kanji (`貴社`) is substituted.
3. **Execution Time**: The substitution runs via a fast Aho-Corasick or Regex replacement in under **0.5 milliseconds** on Android CPU.

---

### Stage 3: Surface Normalizer (Exact Styling)

For terms like English acronyms or mixed casing (`DX`, `ReazonSpeech`, `JotInput`, `ChatGPT`), Stage 3 handles case sensitivity and formatting variations:
- Case-insensitive regex matches `reazonspeech`, `Reazonspeech`, or `REAZONSPEECH` and normalizes to the exact user casing `ReazonSpeech`.
- Handles half-width / full-width kana and alphanumeric characters (`ＤＸ` $\rightarrow$ `DX`).

---

## 📱 4. Data Model & User Interface Design

### Data Model (`DictionaryEntry`):

```kotlin
data class DictionaryEntry(
    val id: Long = 0,
    val word: String,           // Surface form: e.g. "中村", "ReazonSpeech", "貴社"
    val reading: String,        // Reading in Kana: e.g. "なかむら", "れいぞんすぴーち", "きしゃ"
    val priority: Priority = Priority.NORMAL, // LOW (+1.5), NORMAL (+3.0), HIGH (+4.5)
    val isEnabled: Boolean = true
)

enum class Priority(val score: Float) {
    LOW(1.5f),
    NORMAL(3.0f),
    HIGH(4.5f)
}
```

### Automatic Kana Normalization Helper:
```kotlin
object JapaneseKanaUtils {
    // Converts Katakana to Hiragana for uniform matching
    fun katakanaToHiragana(text: String): String {
        return text.map { ch ->
            if (ch in '\u30A1'..'\u30F6') (ch - 0x60) else ch
        }.joinToString("")
    }

    // Converts Hiragana to Katakana
    fun hiraganaToKatakana(text: String): String {
        return text.map { ch ->
            if (ch in '\u3041'..'\u3096') (ch + 0x60) else ch
        }.joinToString("")
    }
}
```

### UI Input Flow:
When the user adds a new word in the app:
1. **Word Field (単語)**: e.g. `中村`
2. **Reading Field (よみ)**: e.g. `なかむら` (Input method keyboard automatically supplies Hiragana)
3. **Priority Selector (優先度)**: Standard / High
4. **Validation**:
   - Ensures `word` is not blank.
   - Ensures `reading` contains valid Hiragana or Katakana.
5. **Immediate Hotword Generation**:
   - Saves to local SQLite/Room database.
   - Immediately regenerates the in-memory/on-disk `hotwords.txt` for the next audio recording session.

---

## 📅 5. Implementation Plan

```mermaid
flowchart TD
    subgraph Phase 1: Core Engine Integration
        A1[Switch AsrManager to modified_beam_search] --> A2[Implement Hotword File Generator]
        A2 --> A3[Feed Word + Reading pairs into Hotwords]
    end

    subgraph Phase 2: Post-Processing Filter
        B1[Implement KanaNormalizer utility] --> B2[Build Fast Regex/Trie Replacer]
        B2 --> B3[Chain Replacer after recognizer.decode]
    end

    subgraph Phase 3: Mobile UI & Persistence
        C1[Room Database for DictionaryEntry] --> C2[Dictionary Management Activity/Dialog]
        C2 --> C3[Import / Export JSON & CSV]
    end

    Phase 1 --> Phase 2 --> Phase 3
```

### Phase 1: Core Engine Integration
- Update [`AsrManager.kt`](app/src/main/java/com/example/myasr/AsrManager.kt):
  - Change `decodingMethod` from `"greedy_search"` to `"modified_beam_search"`.
  - Add support for generating and loading `hotwords.txt` dynamically from `(Word, Reading)` pairs.
  - Test accuracy improvement on proper nouns with ARCVM / test recordings.

### Phase 2: Post-Processing Filter
- Create `DictionaryPostProcessor.kt`:
  - Accepts raw decoded string from Sherpa-ONNX.
  - Replaces all occurrences of `reading` (Hiragana and Katakana) with `word`.
  - Applies case-insensitive regex for Latin/Acronym terms.

### Phase 3: Mobile UI & Local Storage
- Add `DictionaryActivity` / BottomSheetDialog in the Android app:
  - Form with 2 fields: `単語 (Word)` and `よみ (Reading)`.
  - Recycler view displaying registered words with delete and toggle switches.
  - Persists in SQLite (`Room` or `SharedPreferences` JSON for initial prototype).
