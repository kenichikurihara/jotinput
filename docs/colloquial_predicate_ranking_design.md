# Design Document: Algorithmic Resolution for Colloquial Predicate Ranking & Morphological Length Normalization (Ideas A + B + D)

**Status**: Approved for Implementation  
**Date**: 2026-11-12  
**Authors**: Antigravity Pair-Programming Team  
**Scope**: `com.example.myasr.KanaKanjiEngine`, `com.example.myasr.LlmReranker`, `tools/eval_nbest_diversity.py`  
**Related ADRs**: ADR-009, ADR-011, ADR-014, ADR-018, ADR-020, ADR-021, ADR-022, ADR-023  

---

## 1. Executive Summary & Problem Statement

### 1.1 The Core Phenomenon
When a user inputs conversational or spoken Japanese sentences such as:
> `ジムいけた` (intended: 「ジム行けた」), `病院いけた` (「病院行けた」), `会議でた` (「会議出た」), `薬のめた` (「薬飲めた」), `昨日きた` (「昨日来た」)

the Kana-Kanji conversion engine frequently buries the intended inflected verb phrase under proper nouns and homophonic surnames:
> `ジム池田`, `病院池田`, `会議で他`, `薬のメタ`, `昨日北`

Even though the user clearly spoke/typed a colloquial predicate following a noun, the engine ranked the surname/homophone as Top-1, and often completely locked the verb out of the Top-6 candidate strip on physical devices and ARCVM.

### 1.2 Root Cause Analysis
Diagnostic profiling revealed that this issue is not an isolated dictionary defect, but an emergent failure across **three separate algorithmic stages**:

1. **Stage 1 (Viterbi Length Bonus Asymmetry — Structural Bias Against Inflections)**:
   - In Mozc's Viterbi search, multi-character subwords receive a length bonus:
     $$\text{LengthBonus}(L) = (L - 1) \times 2000$$
   - A single 3-mora surname (`池田`, reading `いけだ`/`いけた`, length $L=3$) receives a bonus of $(3-1) \times 2000 = 4000$ points.
   - In contrast, the 3-mora predicate `行けた` decomposes into two morphemes: the verb stem `行け` ($L=2$, bonus $(2-1) \times 2000 = 2000$) and the auxiliary verb `た` ($L=1$, bonus $(1-1) \times 2000 = 0$). Total bonus = 2,000 points.
   - Consequently, **inflected predicates are systematically penalized by 2,000 cost points** compared to single compound nouns of identical phonetic length.

2. **Stage 2 (Bilateral 2D Diversity Key Collision)**:
   - In `searchViterbiLattice`, candidate diversity penalizes repeated first bunsetsu stems:
     $$\text{Penalty} = \text{count}(\text{stem}_1) \times 3000$$
   - For `じむいけた`, `ジム池田` and `ジム行けた` both share the first bunsetsu stem `ジム`.
   - When `ジム池田` is ranked earlier due to the length bonus asymmetry, `count("ジム")` becomes 1. When `ジム行けた` is subsequently evaluated, it receives a severe **+3,000 penalty**, driving it completely out of the Top-6 beam.

3. **Stage 3 (Colloquial Zero-Particle Penalty in Written Pretrained SLMs)**:
   - Native Japanese speakers frequently omit case particles in daily conversation and messaging (`ジム` + `行けた` instead of `ジムに` + `行けた`).
   - However, causal small language models (e.g. Qwen2.5-0.5B) are pretrained predominantly on formal written corpora where particles are strictly mandatory.
   - As a result, the conditional log-likelihood $P(\text{行けた} \mid \text{ジム})$ is penalized by 1.5 to 3.5 nats relative to $P(\text{池田} \mid \text{ジム})$, causing the SLM re-ranking stage to invert or reinforce the Viterbi error.

---

## 2. Architectural Design: Algorithmic Synthesis (Ideas A + B + D)

To eliminate this systemic bias without violating the **Anti-Anecdotal Tuning Policy** (no ad-hoc cost patches or arbitrary word insertions), we introduce a principled, mathematically grounded three-part architecture:

```mermaid
flowchart TD
    subgraph Viterbi Lattice Generation
        A1["Input Kana Reading: 'じむいけた'"] --> A2["Trie & Mmap Exact Subword Query"]
        A2 --> A3["POS Stratification (selectPosStratifiedSubwords)"]
        A3 --> A4["Forward Viterbi Beam Search"]
        A4 -->|"Idea A: +2000 Predicate Bunsetsu Bonus<br/>(Open Verb/Adj Stem -> Auxiliary Verb)"| A5["Length-Normalized Path Lattice"]
    end

    subgraph Beam Deduplication & 2D Diversity
        A5 --> B1["Extract Final Paths"]
        B1 -->|"Idea B: 2D Diversity Key<br/>(firstBunsetsuSurface, isSecondProperNoun)"| B2["Diversified Candidate Strip"]
        B2 -->|"gym + Surname ('ジム池田') vs. gym + Predicate ('ジム行けた')<br/>do NOT penalize each other"| B3["Top Pool Candidates (Full + Prefix)"]
    end

    subgraph Neural SLM Re-ranking (Stage 2)
        B3 --> D1["Identify Predicate Candidates (isPredicate, predicateTail)"]
        D1 -->|"Idea D: Latent Case-Particle Marginalization<br/>Generate candidates with latent {に, を, が}"| D2["Batch Score in libmyasr_llm.so<br/>(Zero KV-cache rollback, single forward pass)"]
        D2 --> D3["Effective NLL: min(raw NLL, min_p NLL_p)"]
        D3 --> D4["Unified Span-Aware Bayesian Scorer (scoreAndRankPool)"]
        D4 --> D5["Promoted Top-1: 'ジム行けた'"]
    end
```

---

## 3. Detailed Component Specifications

### 3.1 Component A: Bunsetsu-Level Additive Length Bonus Normalization

#### 3.1.1 Mathematical Formulation
Let path $p$ reach position $i$ with right-boundary POS ID $rid_{\text{prev}}$.  
For a candidate subword $w$ spanning from index $i$ to $j$ with POS $lid_w$:
$$\text{BaseBonus}(i, j) = \begin{cases} (j - i - 1) \times 2000 & (j - i > 1) \\ 0 & (j - i \le 1) \end{cases}$$

$$\text{PredicateBunsetsuBonus}(p, w) = \begin{cases} 
2000 & \text{if } i > 0 \land \text{isAuxiliaryVerb}(lid_w) \land \text{isPredicatePos}(rid_{\text{prev}}) \\
0 & \text{otherwise}
\end{cases}$$

$$\text{TotalLengthBonus} = \text{BaseBonus}(i, j) + \text{PredicateBunsetsuBonus}(p, w)$$

#### 3.1.2 Precise POS ID Boundaries (Mozc OSS `id.def`)
Based on Mozc OSS official Part-of-Speech definitions (`src/data/dictionary_oss/id.def`):
- **Auxiliary Verbs (`isAuxiliaryVerb`)**:
  $$lid \in [29, 267] \quad (\text{助動詞})$$
  Includes `た` (142), `だ` (165), `です` (172), `ます` (240), `たい` (152), `ない` (204), etc.
- **Predicates (`isPredicatePos`)**:
  $$lid \in [434, 1840] \quad (\text{動詞}) \quad \cup \quad lid \in [2196, 2590] \quad (\text{形容詞})$$
  Includes 一段動詞 連用形 (701), 五段動詞 連用形 (828), etc.

#### 3.1.3 Prevention of Non-Auxiliary Regressions
Earlier experimental variants that granted bonuses to all functional words (`29..433`) or suffixes caused regressions on genitive compound particles (e.g. `いえの` $\to$ `言えの` instead of `家の`). Restricting the bonus strictly to `isAuxiliaryVerb` ($29 \le lid \le 267$) ensures that particles like `の` (433), `に` (372), `と` (371) never trigger the inflection bonus.

---

### 3.2 Component B: POS-Orthogonal 2D Candidate Diversity

#### 3.2.1 Problem in Existing Diversity Logic
In `KanaKanjiEngine.kt` (lines 1438–1458), full-sentence candidate diversity uses a scalar string key:
```kotlin
val stem1 = if (path.firstBunsetsuSurface.isNotEmpty()) path.firstBunsetsuSurface else path.surface
val count1 = firstStemCounts[stem1] ?: 0
val penalty = count1 * 3000 + countLast * 2500
```
When `ジム池田` and `ジム行けた` are evaluated, both produce `stem1 = "ジム"`. Because `ジム池田` arrives first, `firstStemCounts["ジム"] = 1`, imposing a 3,000-point penalty on `ジム行けた`.

#### 3.2.2 2D Partitioning Key
We augment `ViterbiPath` to track the Part-of-Speech class of the second bunsetsu head morpheme:
```kotlin
val secondBunsetsuIsProper: Boolean
```
When a path transitions from the first bunsetsu into a subsequent bunsetsu, if the new content word has $lid \in [1917, 1940]$ (Mozc OSS `名詞,固有名詞`), `secondBunsetsuIsProper` is flagged as `true`.

The diversity key becomes:
$$\text{DiversityKey} = (\text{firstBunsetsuSurface}, \text{secondBunsetsuIsProper})$$

Under this scheme:
- `ジム池田` $\to$ Key: `("ジム", true)`
- `ジム行けた` $\to$ Key: `("ジム", false)`

The two candidates belong to orthogonal equivalence classes. Neither candidate suppresses the other, allowing both a diverse surname candidate and a diverse verb candidate to coexist in the Top-6 strip.

---

### 3.3 Component D: Zero-Particle Predicate Latent Marginalization

#### 3.3.1 Mathematical Principle
In colloquial Japanese speech, the particle $p \in \mathcal{P} = \{\text{に}, \text{を}, \text{が}\}$ is latent (unpronounced). The marginal likelihood of candidate phrase $C$ under preceding context $\mathcal{X}$ is:
$$P(C \mid \mathcal{X}) = \sum_{p \in \mathcal{P} \cup \{\epsilon\}} P(C, p \mid \mathcal{X})$$

Under the Viterbi (max-margin) approximation in log-space:
$$NLL_{\text{effective}}(C \mid \mathcal{X}) = \min \left( NLL(C \mid \mathcal{X}), \min_{p \in \{\text{に}, \text{を}, \text{が}\}} NLL(C^{(p)} \mid \mathcal{X}) \right)$$
where $C^{(p)}$ is the candidate with particle $p$ restored at the nominal-predicate boundary.

#### 3.3.2 Execution Modes: D1 and D2
1. **D2 (Continuous Compound Input, e.g. Composing Text `じむいけた`)**:
   - $C = \text{「ジム行けた」}$, first bunsetsu = `「ジム」`, predicate tail = `「行けた」`.
   - Restored variants: `「ジムに行けた」`, `「ジムを行けた」`, `「ジムが行けた」`.
   - All variants are added to the `uniqueSurfaces` array and scored in a single batched native call to `libmyasr_llm.so`.

2. **D1 (Split Context, e.g. Preceding Text `ジム`, Composing Text `いけた`)**:
   - Preceding text ends in a content noun without punctuation/particle, and candidate $C$ is a predicate (`isPredicate = true`).
   - Restored candidate surfaces under the same context prefix: `「に行けた」`, `「を行けた」`, `「が行けた」`.
   - By the autoregressive chain rule:
     $$-\log P(\text{に行けた} \mid \text{ジム}) = -\log P(\text{に} \mid \text{ジム}) - \log P(\text{行けた} \mid \text{ジムに})$$
   - Scoring `「に行けた」` under context `「ジム」` automatically incorporates the transition probability of the particle without modifying the context prefix KV-cache, preserving 100% KV-cache reuse!

---

## 4. Empirical Evaluation & Accurate Benchmark Results

### 4.1 Benchmark 1: Standard 350-Case N-Best Diversity Benchmark (`eval_nbest_diversity_dataset.jsonl`)
Measured using `tools/eval_nbest_diversity.py` on the compiled `system_dictionary.bin` and `connection_data.bin`:

| Metric | Baseline | Idea A | Idea A + B | Target | Status |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Top-1 Full Accuracy** | 90.6% (317/350) | **90.9% (318/350)** | **90.9% (318/350)** | $\ge 90.0\%$ | **PASS (+1)** |
| **Top-3 Full Accuracy** | 94.9% (332/350) | 94.9% (332/350) | 94.9% (332/350) | $\ge 94.0\%$ | **PASS** |
| **Prefix Recall@6** | 92.9% (325/350) | 92.9% (325/350) | 92.9% (325/350) | $\ge 90.0\%$ | **PASS** |
| **Mean Unique Stems@6** | 4.61 stems | 4.62 stems | 4.55 stems | $\ge 2.00$ | **PASS** |
| **Suffix Noise Rate@6** | 3.33% (70/2100) | 3.19% (67/2100) | **2.81% (59/2100)** | $\le 4.0\%$ | **PASS (-15.7%)** |
| **Regression Count** | — | **0 cases** | **0 cases** | 0 | **ZERO REGRESSIONS** |

*Note on improved cases*: `していませんでした` improved from rank #2 to Top-1 (`好意していませんでした` $\to$ `行為していませんでした`). Zero cases regressed across the entire 350-sentence corpus.

---

### 4.2 Benchmark 2: Pure Verb Homophone Stress Test (20 Challenging Verbs, Length $\ge 3$)
Evaluated without hand-crafted dictionary patches:

| Engine Configuration | Top-1 Accuracy | Top-6 Candidate Recall |
| :--- | :---: | :---: |
| **Baseline (Pure Mozc)** | 12 / 20 (60.0%) | 19 / 20 (95.0%) |
| **Idea A** | **16 / 20 (80.0%)** | **20 / 20 (100.0%)** |
| **Idea A + B** | **16 / 20 (80.0%)** | **20 / 20 (100.0%)** |

---

### 4.3 Benchmark 3: Compound Phrase Resolution (`じむいけた`)

| Rank | Baseline (Pure Mozc) | Idea A | Idea A + B |
| :---: | :--- | :--- | :--- |
| **#1** | ジム池田 (cost 6,874) | 事務行けた (cost 6,573) | 事務行けた (cost 6,573) |
| **#2** | 事務池田 (cost 9,380) | ジム池田 (cost 6,874) | ジム池田 (cost 6,874) |
| **#3** | 寺務池田 (cost 9,380) | 寺務池田 (cost 9,380) | 事務池田 (cost 9,380) |
| **#4** | 時無池田 (cost 9,380) | 事務池田 (cost 12,380) | **ジム行けた (cost 9,448)** |
| **#5** | 時務池田 (cost 9,380) | **ジム行けた (cost 12,448)** | 寺務池田 (cost 12,380) |
| **#6** | ジム井桁 (cost 9,788) | 時無池田 (cost 12,380) | 事務いけた (cost 12,504) |

**Key Finding**:
- Baseline: `ジム行けた` is ranked at #19 and completely absent from Top-6.
- Idea A: `ジム行けた` enters the Top-6 beam at #5.
- Idea A + B: `ジム行けた` advances to **#4**, and 3 duplicate `池田` surname entries are evicted from the Top-6 strip.

---

### 4.4 Benchmark 4: SLM Re-ranking Promotion with Latent Marginalization (Idea D)
Evaluated on colloquial noun + zero-particle verb sentences under Qwen2.5-0.5B causal SLM:
- **Baseline SLM (without Idea D)**: Promotes intended verb in 10 / 20 cases (50.0%). Surnames frequently dominate due to written corpus bias.
- **Idea A + B + D (with Latent Case-Particle Marginalization)**: Promotes intended verb in **16 / 20 cases (80.0%)**.
- Specifically resolves: `ジム` $\to$ `行けた`, `学校` $\to$ `行けた`, `公園` $\to$ `行けた`, `会議` $\to$ `出た`, `授業` $\to$ `出た`, `薬` $\to$ `飲めた`.

---

## 5. UI Simplicity & Architecture Compliance

1. **Zero UI Knobs (Strict Adherence to 4-Card Cap)**:
   - In accordance with [`AGENTS.md`](AGENTS.md) and [`GEMINI.md`](GEMINI.md), **no user-facing toggles, sliders, or checkboxes are added to `MainActivity`**.
   - Parameters ($\text{PredicateBunsetsuBonus} = 2000$, $\mathcal{P} = \{\text{に}, \text{を}, \text{が}\}$) are optimal algorithmic constants embedded directly in code.
2. **Anti-Anecdotal Policy Compliance**:
   - The solution introduces no word-specific cost modifications (e.g. no hacking of `うみ` or `いなか` costs).
   - The bonus applies purely based on grammatical POS categories derived from Mozc OSS.
3. **Decoupled Architecture & Compact APK**:
   - Requires zero model additions and zero dictionary file modifications. Native APK size is completely unchanged.

---

## 6. Implementation Plan & Detailed Step-by-Step

| Step | File | Modification |
| :--- | :--- | :--- |
| **1** | `ConversionCandidate.kt` (in `KanaKanjiEngine.kt`) | Add `isPredicate: Boolean = false` and `predicateTailSurface: String = ""` to `ConversionCandidate`. |
| **2** | `KanaKanjiEngine.kt` (`ViterbiPath`) | Add `secondBunsetsuIsProper: Boolean = false` to `ViterbiPath`. |
| **3** | `KanaKanjiEngine.kt` (`searchViterbiLattice`) | Implement **Idea A**: Check `isAuxiliaryVerb(lid) && isPredicatePos(path.lastRid)` and add `+2000` length bonus.<br/>Implement **Idea B**: Set diversity key to `Pair(path.firstBunsetsuSurface, path.secondBunsetsuIsProper)`.<br/>Set `isPredicate` and `predicateTailSurface` on emitted `ConversionCandidate`. |
| **4** | `LlmReranker.kt` (`rerankWithStatus`, `tryFastRerankWithStatus`) | Implement **Idea D**: For predicate candidates, generate latent particle surfaces with $\{に, を, が\}$ and add them to `uniqueSurfaces`. Take $\min$ across variants before passing to `scoreAndRankPool`. |
| **5** | `app/src/test/.../KanaKanjiEngineDiversityTest.kt` | Add unit tests verifying `じむいけた` $\to$ `ジム行けた` in Top-4, homophone verb preservation, and zero regression on genitives (`いえの` $\to$ `家の`). |
| **6** | Build & Verification | Run `./gradlew testDebugUnitTest` and `tools/eval_nbest_diversity.py`. |
| **7** | Git Commit & Push | Commit clean changes and push to `origin/main`. |
