# Gboard-Inspired Flick-Only & English QWERTY Keyboard Plan

This document specifies the detailed design, layout geometry, key-mapping tables, touch interaction state machines, user preferences, and verification procedures for upgrading **JotInput**'s soft keyboard ([`VoiceInputMethodService`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) & [`FlickKeyboardView`](app/src/main/java/com/example/myasr/FlickKeyboardView.kt)) to mimic **Gboard's Flick-Only Japanese layout** and **Gboard's English QWERTY layout** (with an optional top Number Row `1–0`).

---

## 1. Empirical Analysis of Real Gboard on Pixel 10 Pro

Using `adb` on the connected Pixel 10 Pro (`<device-serial>`), we captured and inspected real Gboard (`com.google.android.inputmethod.latin`) across all layout states:

### 1.1 Gboard Japanese 12-Key Flick-Only Layout (`gboard_1.png`, `gboard_flick_composing.png`)
- **Clean Key Faces (No Sub-labels on Kana Keys)**:
  - Unlike legacy multi-tap keyboards that clutter each key with `いうえお` sub-labels, Gboard's flick-only layout renders **only the primary character** (`あ`, `か`, `さ`, `た`, `な`, `は`, `ま`, `や`, `ら`, `わ`) centered in a clean, large font, separated by subtle vertical hairlines between the left/right function columns and the 3-column center grid.
- **5-Column × 4-Row Grid Structure**:
  | Row | Col 0 (Left Fn ~15%) | Col 1 (Kana ~23.3%) | Col 2 (Kana ~23.3%) | Col 3 (Kana ~23.3%) | Col 4 (Right Fn ~15%) |
  | :--- | :--- | :--- | :--- | :--- | :--- |
  | **Row 0** | `↶` (Undo / 戻す) | `あ` (`あ い う え お`) | `か` (`か き く け こ`) | `さ` (`さ し す せ そ`) | `⌫` (Delete) |
  | **Row 1** | `◀` (Cursor Left) | `た` (`た ち つ て と`) | `な` (`な に ぬ ね の`) | `は` (`は ひ ふ へ ほ`) | `▶` (Cursor Right) |
  | **Row 2** | `?123` (Symbols/Num) | `ま` (`ま み む め も`) | `や` (`や （ ゆ ） よ`) | `ら` (`ら り る れ ろ`) | `␣` (Idle) / `変換` (Composing) |
  | **Row 3** | `あa` (Pill: Kana $\leftrightarrow$ QWERTY) | `゛゜` / `大⇔小` (Modifier) | `わ` (`わ を ん ー 〜`) | `、。？！…` (5-dir Punc graphic) | `↵` / `確定` (Accent Pill) |
- **Modifier Key (`゛゜ / 大⇔小` at Row 3, Col 1)**:
  - Tap (`CENTER`): Cycles dakuten $\rightarrow$ handakuten $\rightarrow$ small kana $\rightarrow$ original (`は` $\rightarrow$ `ば` $\rightarrow$ `ぱ` $\rightarrow$ `は`, `つ` $\rightarrow$ `っ` $\rightarrow$ `づ` $\rightarrow$ `つ`, `あ` $\rightarrow$ `ぁ` $\rightarrow$ `あ`).
  - Directional Flicks on Modifier Key (matching Gboard's `gboard_mod_test.png` behavior):
    - Flick **LEFT** (`゛`): Applies dakuten directly (`は` $\rightarrow$ `ば`, `か` $\rightarrow$ `が`).
    - Flick **RIGHT** (`゜`): Applies handakuten directly (`は` $\rightarrow$ `ぱ`, `ば` $\rightarrow$ `ぱ`).
    - Flick **UP** or **DOWN** (`小`): Toggles small/large kana directly (`つ` $\leftrightarrow$ `っ`, `や` $\leftrightarrow$ `ゃ`, `あ` $\leftrightarrow$ `ぁ`).
- **Punctuation Key (`、。？！…` at Row 3, Col 3)**:
  - Renders Gboard's iconic 4-direction + center graphic on the key face (`?` top, `。` left, `、` center, `!` right, `…` bottom).
  - Flick mapping: Center = `、`, Left = `。`, Up = `？`, Right = `！`, Down = `…`.
- **5-Petal Circular Flick Popup (`gboard_flick_popup_a.png`)**:
  - Pressing a flick key highlights the key rectangle and displays a floating circular guide above the key showing the center character in large font surrounded by the 4 directional characters (`い` left, `う` top, `え` right, `お` bottom) inside a single unified circle, shifting to a highlighted directional badge when dragged past the flick threshold.

---

### 1.2 Gboard English QWERTY Layout (`gboard_after_aa.png`, `gboard_qwerty_shift.png`)
- **Mode Switching (`あa` Pill)**:
  - Tapping `あa` in the bottom-left corner of the Japanese 12-key Flick keyboard switches immediately to the **English QWERTY** keyboard (`あa` pill with `a` emphasized).
  - Tapping `あa` in the bottom-left corner of the English QWERTY keyboard switches back to the **Japanese 12-key Flick** keyboard (`あa` pill with `あ` emphasized).
- **Optional Number Row (`1–0`)**:
  - When **Show Number Row = ON** (5 rows total):
    - **Row 0 (Number Row)**: `1` `2` `3` `4` `5` `6` `7` `8` `9` `0` (10 equal-width keys, 10% width each).
  - When **Show Number Row = OFF** (4 rows total):
    - Row 0 is omitted; the 4 alphabet/control rows expand vertically to share the keyboard height, with top-right sub-labels `1`–`0` shown on `q`–`p`.
- **Alphabet & Control Rows**:
  - **Row A (`q–p`, 10 keys, 10% width each)**:
    - Keys: `q` `w` `e` `r` `t` `y` `u` `i` `o` `p` (uppercase `Q–P` when Shift/Caps is active).
    - Top-right sub-labels (matching Gboard):
      - When Number Row is ON: `%` `\` `|` `=` `[` `]` `<` `>` `{` `}`
      - When Number Row is OFF: `1` `2` `3` `4` `5` `6` `7` `8` `9` `0`
  - **Row B (`a–l`, 9 keys, 10% width each, 5% horizontal inset on left & right)**:
    - Keys: `a` `s` `d` `f` `g` `h` `j` `k` `l` (uppercase `A–L` when Shift/Caps is active).
    - Top-right sub-labels: `@` `#` `¥` `-` `&` `_` `+` `(` `)`
  - **Row C (Shift + `z–m` + Delete, 9 keys)**:
    - Left (`15%` width): `⇧` Shift key (3 states: `OFF` $\rightarrow$ `SHIFT_ONCE` $\rightarrow$ `CAPS_LOCK` via double-tap).
    - Center 7 keys (`10%` width each, from `15%` to `85%`): `z` `x` `c` `v` `b` `n` `m`
      - Top-right sub-labels: `*` `"` `'` `:` `;` `!` `?`
    - Right (`15%` width): `⌫` Delete key.
  - **Row D (Bottom Function Row)**:
    - `あa` (`13%` width): Pill button switching back to Japanese 12-key Flick.
    - `?123` (`11%` width): Switches to Symbols / Numpad layer.
    - `,` (`9%` width): Commits `,`.
    - `Space` (`34%` width): Pill spacebar labeled `English` (or `空白` / current mode).
    - `.` (`9%` width): Commits `.`.
    - `◀` (`5.5%` width) & `▶` (`5.5%` width): Cursor left / right (or `🌐` Globe + `◀`/`▶` — see Section 2.3 for how we preserve quick access to `🌐` and `📖` without clutter).
    - `↵` (`13%` width): Accent pill Enter/Action key.

---

### 1.3 Gboard Symbols & 12-Key Numpad Layouts (`gboard_sym1.png`, `gboard_numpad.png`)
- **QWERTY Symbols Layer (`?123` from QWERTY)**:
  - Row 0: `1` `2` `3` `4` `5` `6` `7` `8` `9` `0`
  - Row 1: `@` `#` `¥` `%` `&` `-` `+` `(` `)` `/`
  - Row 2: `=\<` (15%), `*` `"` `'` `:` `;` `!` `?` (10% each), `⌫` (15%)
  - Row 3: `ABC` / `あa` (13%), `,` (9%), `1234` Numpad switch (11%), `Space` (34%), `.` (9%), `◀` / `▶` (11%), `↵` (13%)
- **12-Key Numpad Layer (`?123` from Flick, or `1234` from Symbols)**:
  - Matches `gboard_numpad.png`:
  - Col 0 (Left Fn 15%): Row 0 `+`, Row 1 `-`, Row 2 `*`, Row 3 `/` (or `あいう` back button at bottom-left).
  - Center 3×4 Grid:
    - Row 0: `1` `2` `3`
    - Row 1: `4` `5` `6`
    - Row 2: `7` `8` `9`
    - Row 3: `, / !?#` `0` `. / =`
  - Col 4 (Right Fn 15%): Row 0 `⌫`, Row 1 `%`, Row 2 `␣`, Row 3 `↵`.

---

## 2. Architecture & Component Design

### 2.1 Persistent Keyboard Preferences (`KeyboardPreferences` / [`KeyboardHeightManager`](app/src/main/java/com/example/myasr/KeyboardHeightManager.kt))
We extend [`KeyboardHeightManager.kt`](app/src/main/java/com/example/myasr/KeyboardHeightManager.kt) (which already manages `ime_preferences` SharedPreferences) to store and retrieve:
1. **`showNumberRowInQwerty: Boolean`** (key `"qwerty_show_number_row"`, default: `true` — matches Gboard screenshot `gboard_after_aa.png` while allowing users to toggle it OFF in [`MainActivity`](app/src/main/java/com/example/myasr/MainActivity.kt) or header).
2. **`lastActiveLayoutMode: String`** (key `"last_keyboard_mode"`, default: `KANA_FLICK`, remembers whether the user was in Japanese Flick or English QWERTY across input sessions unless overridden by `EditorInfo`).

### 2.2 Header Bar Enhancements (`keyboard_view.xml`)
Currently, `keyboardHeader` in [`keyboard_view.xml`](app/src/main/res/layout/keyboard_view.xml) contains:
- `btnVoiceToggle` (`🎙 話す` / `⏹ 停止`)
- `candidateStripView` (horizontal candidate strip)
- `recordingIndicator`

Because Gboard's 12-key Flick and QWERTY layouts dedicate their grid keys to typing and cursor navigation (`↶`, `◀`, `▶`, `?123`, `あa`, `⌫`, `␣`, `↵`), we place compact icon buttons on the right side of `keyboardHeader` (matching Gboard's top utility bar):
- **`btnHeaderGlobe` (`🌐`)**: Always accessible in the top-right of the header bar to switch to the previous system IME / show the IME picker.
- **`btnHeaderDict` (`📖`)**: Always accessible in the top-right of the header bar to open [`DictionaryActivity`](app/src/main/java/com/example/myasr/DictionaryActivity.kt).

This frees all 20 keys of the 12-key Flick grid and all keys of the QWERTY grid to 100% faithfully match Gboard's layout!

### 2.3 Unified Layout Modes in [`JapaneseKanaUtils.KeyboardMode`](app/src/main/java/com/example/myasr/JapaneseKanaUtils.kt)
We update `KeyboardMode` to represent the active layout cleanly:
- `KANA`: Gboard-style 12-key Flick-Only Japanese layout (`あ`–`わ`, `↶`, `◀`, `▶`, `?123`, `あa`, `゛゜大⇔小`, `、。？！…`, `⌫`, `␣`/`変換`, `↵`/`確定`).
- `QWERTY`: Gboard-style English QWERTY layout (with optional `1–0` number row, Shift/Caps Lock, `,`, `.`, `◀`, `▶`, `?123`, `あa`).
- `QWERTY_SYMBOLS`: Gboard-style QWERTY symbols layer (`1–0`, `@ # ¥ % & - + ( ) /`, `* " ' : ; ! ?`, and `1234` numpad button).
- `NUMBER`: Gboard-style 12-key Numpad layout (`1–9`, `0`, `+ - * / % = , .`, `⌫`, `␣`, `↵`, and back to `あいう` / `ABC`).

### 2.4 Composing State & Action Labels
- [`FlickKeyboardView`](app/src/main/java/com/example/myasr/FlickKeyboardView.kt) exposes `setComposingState(isComposing: Boolean, canUndo: Boolean)`:
  - When `isComposing == true` in `KANA` mode:
    - Space key (`Row 2, Col 4`) displays **`変換`** (Convert).
    - Enter key (`Row 3, Col 4`) displays **`確定`** (Commit).
  - When `isComposing == false`:
    - Space key displays **`␣`** (or `空白` / `English` on QWERTY spacebar).
    - Enter key displays **`↵`** (Return arrow).
  - When `canUndo == true` (immediately after committing a conversion or deleting):
    - Top-left key (`Row 0, Col 0`) displays active **`↶`** (Undo) and restores the previous pre-edit reading or reverts the last committed conversion!

---

## 3. Detailed Visual & Touch Specifications

### 3.1 Gboard-Style Flick-Only Visuals (`KANA` & `NUMBER` modes)
1. **Grid Proportions**:
   - Left function column (`Col 0`): `15.5%` of keyboard width.
   - Center 3 columns (`Col 1..3`): `23.0%` each (`69.0%` total).
   - Right function column (`Col 4`): `15.5%` of keyboard width.
   - 4 equal-height rows (`height / 4f`).
2. **Clean Key Rendering**:
   - Kana keys (`あ`..`わ`) render **only** the main character (no `いうえお` sub-labels), using clean, slightly larger typography (`rowHeight * 0.44f`).
   - Vertical divider lines (`1dp` subtle border) separate `Col 0 | Col 1..3 | Col 4`, with crisp rounded key cards or Gboard-style key cells.
   - **Row 3, Col 0 (`あa` Mode Toggle Pill)**: Drawn as a stadium pill (`rx = height / 2`) with dual-tone text (`あ` highlighted in `KANA` mode, `a` highlighted in `QWERTY` mode).
   - **Row 3, Col 1 (`゛゜ / 大⇔小` Modifier Key)**: Custom canvas rendering showing `゛` and `゜` on the upper line and `大⇔小` on the lower line, matching Gboard's icon.
   - **Row 3, Col 3 (`、。？！…` Punctuation Key)**: Custom canvas rendering placing `?` at top, `。` at left, `、` at center-right, `!` at right, and `…` at bottom, matching Gboard's 5-direction punctuation key.
   - **Row 3, Col 4 (`↵` / `確定` Enter Key)**: Drawn as a stadium pill with accent fill (`#1A73E8` / Gboard accent).
3. **Key Repeat on Hold (`⌫`, `◀`, `▶`)**:
   - Holding down `⌫` (Delete), `◀` (Cursor Left), or `▶` (Cursor Right) triggers an initial 400ms delay followed by a 65ms repeat interval using `Handler` / `postDelayed`, stopping immediately on `ACTION_UP` / `ACTION_CANCEL`.

### 3.2 Gboard-Style English QWERTY Visuals & Touch (`QWERTY` & `QWERTY_SYMBOLS` modes)
1. **Row Calculation (`showNumberRow = true` vs `false`)**:
   - When `showNumberRow == true` (or in `QWERTY_SYMBOLS` mode): **5 rows**, each `rowHeight = height / 5f`.
   - When `showNumberRow == false`: **4 rows**, each `rowHeight = height / 4f`.
2. **Key Geometry & Hit-Testing**:
   - Each key has a visual `rect: RectF` (with horizontal gap `2.5dp` and vertical gap `3.5dp`) and a touch `hitRect: RectF` that tiles seamlessly without dead zones between keys so fast typing on narrow QWERTY keys never drops touches.
3. **Top-Right Sub-Labels & Long-Press Symbol Input**:
   - Each QWERTY key renders its primary letter (`q`–`m` or `Q`–`M`) centered and its secondary symbol (`1`–`0` or `% \ | = [ ] < > { } @ # ¥ - & _ + ( ) * " ' : ; ! ?`) in smaller muted text at the top-right corner of the key cap.
   - **Long-Press (300ms) or Flick-Up on a QWERTY letter key**: Inputs the top-right secondary symbol (or number when Number Row is hidden) and shows the symbol in the key preview popup!
4. **QWERTY Key Preview Popup (`FlickPopupWindow`)**:
   - Pressing a QWERTY key shows a clean single-key callout bubble directly above the pressed key via [`FlickPopupWindow`](app/src/main/java/com/example/myasr/FlickPopupWindow.kt) (extending above the IME window for top-row keys without changing IME window height or insets, preserving the fix from **Finding 7** in [`docs/findings_and_decisions.md`](docs/findings_and_decisions.md)).
5. **Shift & Caps Lock State Machine**:
   - `ShiftState.OFF`: Letters are lowercase (`a–z`).
   - Single tap on `⇧` $\rightarrow$ `ShiftState.SHIFT_ONCE`: Letters render uppercase (`A–Z`). Typing any letter commits uppercase and automatically returns to `ShiftState.OFF`.
   - Double tap on `⇧` (within 350ms) or tapping `⇧` while in `SHIFT_ONCE` $\rightarrow$ `ShiftState.CAPS_LOCK`: Letters stay uppercase (`A–Z`) with an underline indicator on `⇧` until `⇧` is tapped again.

---

## 4. User Settings in `MainActivity`

In [`activity_main.xml`](app/src/main/res/layout/activity_main.xml) and [`MainActivity.kt`](app/src/main/java/com/example/myasr/MainActivity.kt) inside the **Voice Keyboard (音声キーボード)** configuration card (right below the Keyboard Height selector):
1. **Default Keyboard Layout Mode (`初期キーボード配列`)**:
   - RadioGroup / Segmented choice: `日本語 12キー (Flick)` vs. `英語 QWERTY`.
2. **QWERTY Number Row Option (`英語QWERTY配列の数字キー行 1〜0`)**:
   - `MaterialSwitch` / `CheckBox` (`switchQwertyNumberRow`): **"英語キーボード上部に数字行 (1〜0) を表示する (Show number row on QWERTY)"**.
   - Toggling this option immediately saves to `KeyboardHeightManager` (`ime_preferences`) and refreshes the active IME view when `etKeyboardTest` is focused.

---

## 5. Self-Review & Critical Edge-Case Iteration

During self-review of this plan against [`docs/findings_and_decisions.md`](docs/findings_and_decisions.md), we identified and resolved the following edge cases:

1. **Edge Case 1 — Top-Row Popup Clipping on 5-Row QWERTY (`1–0` or `q–p`)**:
   - *Risk*: In 5-row QWERTY mode, Row 0 (`1–0`) sits at the very top of `FlickKeyboardView`. If the key preview popup is drawn in-canvas, it will be clipped by the top of the view. If drawn via an in-window overlay, it would trigger the Android 17 `SoftInputWindow` jump (**Finding 7**).
   - *Solution*: Extend [`FlickPopupGeometry`](app/src/main/java/com/example/myasr/FlickPopupGeometry.kt), [`FlickPopupContentView`](app/src/main/java/com/example/myasr/FlickPopupContentView.kt), and [`FlickPopupWindow`](app/src/main/java/com/example/myasr/FlickPopupWindow.kt) to support both:
     - **5-Petal Circular Flick Preview** (for `KANA` flick keys, matching Gboard's unified circular guide + active directional petal).
     - **Single-Key Callout Preview** (for `QWERTY`, `QWERTY_SYMBOLS`, and `NUMBER` keys).
     Both use the existing zero-inset `PopupWindow` architecture so the IME window height (`Requested h`) and `mGivenContentInsets=[0,0][0,0]` remain 100% rock-solid!

2. **Edge Case 2 — Cursor Left (`◀`) and Right (`▶`) During Japanese Composing vs. Idle**:
   - *Risk*: If `composingText` is non-empty and the user presses `◀` or `▶`, calling `sendDownUpKeyEvents(DPAD_LEFT)` while `setComposingText` is active can behave inconsistently across target apps.
   - *Solution*:
     - When `composingText` is empty: send `KeyEvent.KEYCODE_DPAD_LEFT` / `KEYCODE_DPAD_RIGHT` (or adjust selection via `InputConnection` if needed).
     - When `composingText` is non-empty: commit the active composing text first (or move cursor cleanly) so the cursor never gets stuck inside uncommitted pre-edit text.

3. **Edge Case 3 — Undo (`↶`) Behavior in Japanese Flick**:
   - *Risk*: Users accustomed to Gboard's top-left `↶` button expect it to undo the last conversion commit (returning the committed kanji back to hiragana composing text) or undo the last character deletion.
   - *Solution*: Maintain `lastCommittedReading: String` and `lastCommittedSurface: String` in [`VoiceInputMethodService`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt). When `onUndoPressed()` is triggered right after committing a candidate, delete `lastCommittedSurface.length` characters before the cursor and restore `lastCommittedReading` into `composingText`!

4. **Edge Case 4 — Keyboard Height Compatibility (`KeyboardHeightManager`)**:
   - *Risk*: When `showNumberRowInQwerty` is enabled (5 rows instead of 4), 5 rows inside the Compact (80%) height might have smaller row heights.
   - *Solution*: Dynamically scale text sizes (`keyTextPaint`, `keySubTextPaint`) based on `rowHeight`, and give 5-row QWERTY a slight proportional height boost (`+12%` within the user's selected height preset clamp) or adaptive row sizing so keys remain comfortable to tap in both 4-row and 5-row modes.

---

## 6. Implementation & Verification Checklist

1. **Step 1**: Update [`JapaneseKanaUtils.kt`](app/src/main/java/com/example/myasr/JapaneseKanaUtils.kt) with:
   - `KeyboardMode` (`KANA`, `QWERTY`, `QWERTY_SYMBOLS`, `NUMBER`).
   - Directional modifier helper (`applyDirectionalModifier(char, direction)` for Center=cycle, Left=dakuten, Right=handakuten, Up/Down=small).
   - QWERTY key definitions, top-right sub-label maps (with and without Number Row), and Symbol/Numpad maps.
2. **Step 2**: Update [`KeyboardHeightManager.kt`](app/src/main/java/com/example/myasr/KeyboardHeightManager.kt) with `isQwertyNumberRowEnabled(context)` / `setQwertyNumberRowEnabled(context, enabled)` and default layout mode persistence.
3. **Step 3**: Update [`FlickPopupGeometry.kt`](app/src/main/java/com/example/myasr/FlickPopupGeometry.kt), [`FlickPopupContentView.kt`](app/src/main/java/com/example/myasr/FlickPopupContentView.kt), and [`FlickPopupWindow.kt`](app/src/main/java/com/example/myasr/FlickPopupWindow.kt) to render Gboard's unified circular flick guide for `KANA` keys and single-key callout preview for `QWERTY` / `NUMBER` keys.
4. **Step 4**: Rewrite [`FlickKeyboardView.kt`](app/src/main/java/com/example/myasr/FlickKeyboardView.kt) to render:
   - Gboard-style 12-key Flick-Only Japanese layout (`↶`, `◀`, `▶`, `?123`, `あa`, `゛゜大⇔小`, `、。？！…`, `⌫`, `␣`/`変換`, `↵`/`確定`, and clean sub-label-free `あ–わ` keys).
   - Gboard-style English QWERTY layout with optional `1–0` number row, Shift/Caps Lock, long-press/flick-up symbols, `,`, `.`, `◀`, `▶`, `あa`, `?123`, `⌫`, `↵`.
   - Gboard-style Symbols (`QWERTY_SYMBOLS`) and 12-key Numpad (`NUMBER`) layouts.
   - Key repeat on hold for `⌫`, `◀`, `▶`.
5. **Step 5**: Update [`keyboard_view.xml`](app/src/main/res/layout/keyboard_view.xml) and [`VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) to wire `onCursorLeft()`, `onCursorRight()`, `onUndoPressed()`, directional modifiers, header `🌐`/`📖` buttons, and preference reloading on `onStartInputView`.
6. **Step 6**: Update [`activity_main.xml`](app/src/main/res/layout/activity_main.xml) and [`MainActivity.kt`](app/src/main/java/com/example/myasr/MainActivity.kt) to add the **"英語QWERTY配列の数字行 (1〜0) を表示"** toggle switch and default mode selector.
7. **Step 7**: Add unit tests in `app/src/test/` covering `JapaneseKanaUtils` directional modifiers, QWERTY key/symbol mappings, and `FlickPopupGeometry`.
8. **Step 8**: Run `./gradlew testDebugUnitTest assembleDebug` and [`./deploy.sh`](deploy.sh) to deploy to **both** `arc:5555` and `<device-serial>`, verifying logcat, `dumpsys window windows`, and visual screenshots (`screencap`) across Flick, QWERTY (with number row ON), and QWERTY (with number row OFF).
