package com.example.myasr.context.clipboard.domain

import com.example.myasr.shared.domain.Judgement
import com.example.myasr.shared.domain.JudgementResult
import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-8 (Clipboard History) におけるクリップボード提案チップ表示可否の
 * 純粋ドメイン DMN DecisionTable / Judgement。
 */
object ClipboardSuggestionJudgement {

    data class SuggestionEligibilityInput(
        val isInputViewActive: Boolean,
        val isVoiceIdle: Boolean,
        val isClipboardHistoryShowing: Boolean,
        val isKeyboardComposing: Boolean,
        val isStreamingComposing: Boolean,
        val isTypingSessionActive: Boolean,
        val hasActiveNonClipboardCandidates: Boolean
    )

    val suggestionEligibilityDecisionTable: DecisionTable<SuggestionEligibilityInput, JudgementResult<Boolean>> =
        decisionTable(
            name = "ClipboardSuggestionEligibility",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "CLIP_R1", description = "Reject when input view is inactive") {
                `when` { !it.isInputViewActive }
                thenValue(JudgementResult(false, "input_view_inactive"))
            }
            rule(id = "CLIP_R2", description = "Reject when voice input is not idle") {
                `when` { !it.isVoiceIdle }
                thenValue(JudgementResult(false, "voice_not_idle"))
            }
            rule(id = "CLIP_R3", description = "Reject when clipboard history sheet is already showing") {
                `when` { it.isClipboardHistoryShowing }
                thenValue(JudgementResult(false, "clipboard_history_showing"))
            }
            rule(id = "CLIP_R4", description = "Reject when keyboard composing is active") {
                `when` { it.isKeyboardComposing }
                thenValue(JudgementResult(false, "keyboard_composing_active"))
            }
            rule(id = "CLIP_R5", description = "Reject when streaming voice composing is active") {
                `when` { it.isStreamingComposing }
                thenValue(JudgementResult(false, "streaming_composing_active"))
            }
            rule(id = "CLIP_R6", description = "Reject during active typing session") {
                `when` { it.isTypingSessionActive }
                thenValue(JudgementResult(false, "typing_session_active"))
            }
            rule(id = "CLIP_R7", description = "Reject when non-clipboard candidates are present") {
                `when` { it.hasActiveNonClipboardCandidates }
                thenValue(JudgementResult(false, "non_clipboard_candidates_present"))
            }
            defaultValue(
                id = "CLIP_DEFAULT",
                description = "Eligible to show fresh clipboard suggestion chip",
                value = JudgementResult(true, "eligible_for_clipboard_suggestion")
            )
        }

    val suggestionEligibilityJudgement: Judgement<SuggestionEligibilityInput, JudgementResult<Boolean>> =
        suggestionEligibilityDecisionTable

    fun judgeEligibility(input: SuggestionEligibilityInput): JudgementResult<Boolean> =
        suggestionEligibilityJudgement.judge(input)

    fun judgeEligibilityWithTrace(input: SuggestionEligibilityInput): DecisionTrace<JudgementResult<Boolean>> =
        suggestionEligibilityDecisionTable.evaluateWithTrace(input)
}
