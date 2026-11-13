package com.example.myasr.shared.domain

/**
 * Shared Kernel: 言語種別を表す値オブジェクト。
 */
enum class InputLanguage {
    JAPANESE,
    ENGLISH,
    OTHER
}

/**
 * Shared Kernel: カスタム語彙の優先度。
 */
enum class VocabularyPriority {
    LOW,
    NORMAL,
    HIGH
}

/**
 * Published Language: Custom Vocabulary コンテキストが外部コンテキストへ公開する語彙モデル。
 * 下流コンテキスト（KanaKanji, Speech, English）はそれぞれの ACL を通じてこれを変換・適用する。
 */
data class VocabularyItem(
    val id: String,
    val word: String,
    val reading: String,
    val priority: VocabularyPriority,
    val isEnabled: Boolean,
    val language: InputLanguage
)

/**
 * Shared Kernel: 変換候補の生成元コンテキスト。
 */
enum class CandidateSource {
    KANA_KANJI,
    SLM_RERANKED,
    ENGLISH_SWIPE,
    ENGLISH_SPELL,
    VOICE_ASR,
    USER_DICTIONARY,
    NEXT_PHRASE,
    CLIPBOARD
}

/**
 * Published Language: 変換・予測コンテキスト間でやり取りされる統一候補値オブジェクト。
 */
data class SharedCandidate(
    val surface: String,
    val reading: String = "",
    val score: Float = 0f,
    val source: CandidateSource = CandidateSource.KANA_KANJI,
    val metadata: Map<String, String> = emptyMap()
)
