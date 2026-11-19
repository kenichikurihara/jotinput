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

    enum class ClipboardDisplayMode {
        PLAIN_TEXT,
        MASKED_SENSITIVE,
        HIDDEN
    }

    data class SafeClipboardHandlingInput(
        val isSensitiveClip: Boolean,
        val isPasswordInputField: Boolean,
        val isVolatileExpired: Boolean,
        val isDismissedFromStrip: Boolean,
        val isDismissedFromSheet: Boolean
    )

    data class SafeClipboardHandlingDecision(
        val allowDiskPersistence: Boolean,
        val allowLearningAndPrediction: Boolean,
        val stripDisplayMode: ClipboardDisplayMode,
        val sheetDisplayMode: ClipboardDisplayMode,
        val reason: String
    )

    val safeClipboardHandlingDecisionTable: DecisionTable<SafeClipboardHandlingInput, SafeClipboardHandlingDecision> =
        decisionTable(
            name = "SafeClipboardHandling",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(id = "SAFE_CLIP_R1", description = "Hide expired sensitive clip and forbid persistence/learning") {
                `when` { it.isSensitiveClip && it.isVolatileExpired }
                thenValue(
                    SafeClipboardHandlingDecision(
                        allowDiskPersistence = false,
                        allowLearningAndPrediction = false,
                        stripDisplayMode = ClipboardDisplayMode.HIDDEN,
                        sheetDisplayMode = ClipboardDisplayMode.HIDDEN,
                        reason = "sensitive_clip_expired"
                    )
                )
            }
            rule(id = "SAFE_CLIP_R2", description = "Hide sensitive clip dismissed from sheet and forbid persistence/learning") {
                `when` { it.isSensitiveClip && it.isDismissedFromSheet }
                thenValue(
                    SafeClipboardHandlingDecision(
                        allowDiskPersistence = false,
                        allowLearningAndPrediction = false,
                        stripDisplayMode = ClipboardDisplayMode.HIDDEN,
                        sheetDisplayMode = ClipboardDisplayMode.HIDDEN,
                        reason = "sensitive_clip_dismissed_from_sheet"
                    )
                )
            }
            rule(id = "SAFE_CLIP_R3", description = "Mask sensitive clip in sheet when dismissed from strip") {
                `when` { it.isSensitiveClip && it.isDismissedFromStrip }
                thenValue(
                    SafeClipboardHandlingDecision(
                        allowDiskPersistence = false,
                        allowLearningAndPrediction = false,
                        stripDisplayMode = ClipboardDisplayMode.HIDDEN,
                        sheetDisplayMode = ClipboardDisplayMode.MASKED_SENSITIVE,
                        reason = "sensitive_clip_strip_dismissed_sheet_masked"
                    )
                )
            }
            rule(id = "SAFE_CLIP_R4", description = "Mask active sensitive clip on both strip and sheet without persistence or learning") {
                `when` { it.isSensitiveClip }
                thenValue(
                    SafeClipboardHandlingDecision(
                        allowDiskPersistence = false,
                        allowLearningAndPrediction = false,
                        stripDisplayMode = ClipboardDisplayMode.MASKED_SENSITIVE,
                        sheetDisplayMode = ClipboardDisplayMode.MASKED_SENSITIVE,
                        reason = "sensitive_clip_masked_volatile"
                    )
                )
            }
            rule(id = "SAFE_CLIP_R5", description = "Forbid learning/prediction when pasting into a password input field") {
                `when` { !it.isSensitiveClip && it.isPasswordInputField }
                then {
                    SafeClipboardHandlingDecision(
                        allowDiskPersistence = true,
                        allowLearningAndPrediction = false,
                        stripDisplayMode = if (it.isDismissedFromStrip) ClipboardDisplayMode.HIDDEN else ClipboardDisplayMode.PLAIN_TEXT,
                        sheetDisplayMode = if (it.isDismissedFromSheet) ClipboardDisplayMode.HIDDEN else ClipboardDisplayMode.PLAIN_TEXT,
                        reason = "password_field_no_learning"
                    )
                }
            }
            default {
                SafeClipboardHandlingDecision(
                    allowDiskPersistence = true,
                    allowLearningAndPrediction = true,
                    stripDisplayMode = if (it.isDismissedFromStrip) ClipboardDisplayMode.HIDDEN else ClipboardDisplayMode.PLAIN_TEXT,
                    sheetDisplayMode = if (it.isDismissedFromSheet) ClipboardDisplayMode.HIDDEN else ClipboardDisplayMode.PLAIN_TEXT,
                    reason = "standard_clip_allowed"
                )
            }
        }

    fun judgeSafeHandling(input: SafeClipboardHandlingInput): SafeClipboardHandlingDecision =
        safeClipboardHandlingDecisionTable.evaluate(input)

    fun judgeSafeHandlingWithTrace(input: SafeClipboardHandlingInput): DecisionTrace<SafeClipboardHandlingDecision> =
        safeClipboardHandlingDecisionTable.evaluateWithTrace(input)
}
