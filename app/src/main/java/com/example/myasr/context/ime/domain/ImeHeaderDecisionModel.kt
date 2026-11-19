package com.example.myasr.context.ime.domain

import com.example.myasr.shared.domain.dmn.DecisionTable
import com.example.myasr.shared.domain.dmn.DecisionTrace
import com.example.myasr.shared.domain.dmn.HitPolicy
import com.example.myasr.shared.domain.dmn.decisionTable

/**
 * BC-7 (IME Session & Main App UI) における IME ヘッダーアイコン（マイク・辞書・クリップボード）
 * の表示／非表示を裁定する純粋ドメイン DMN DecisionModel。
 *
 * フリック入力（キー押下中）および未確定文字列（Composing Text）存在時は、
 * ユーティリティアイコンを隠して候補ストリップの表示幅・候補表示数を最大化する。
 * ただし音声録音中・デコード中は停止／進捗操作を保護するためマイクボタン領域を維持する。
 */
object ImeHeaderDecisionModel {

    enum class VoiceState {
        IDLE,
        RECORDING,
        DECODING
    }

    data class ImeHeaderVisibilityInput(
        val voiceState: VoiceState,
        val isKeyboardComposing: Boolean,
        val isStreamingComposing: Boolean,
        val isKeyTouchActive: Boolean,
        val isClipboardHistoryShowing: Boolean
    ) {
        val isInputOrComposingActive: Boolean
            get() = isKeyboardComposing || isStreamingComposing || isKeyTouchActive
    }

    data class ImeHeaderVisibilityDecision(
        val showVoiceToggle: Boolean,
        val showDictionaryButton: Boolean,
        val showClipboardButton: Boolean
    )

    val headerVisibilityDecisionTable: DecisionTable<ImeHeaderVisibilityInput, ImeHeaderVisibilityDecision> =
        decisionTable(
            name = "ImeHeaderVisibilityDecisionTable",
            hitPolicy = HitPolicy.FIRST
        ) {
            rule(
                id = "HEADER_R1",
                description = "Keep all header utility icons visible while clipboard history overlay is open"
            ) {
                `when` { it.isClipboardHistoryShowing }
                thenValue(
                    ImeHeaderVisibilityDecision(
                        showVoiceToggle = true,
                        showDictionaryButton = true,
                        showClipboardButton = true
                    )
                )
            }

            rule(
                id = "HEADER_R2",
                description = "During voice recording/decoding with active composing or key touch, keep voice toggle visible and hide dictionary/clipboard"
            ) {
                `when` { it.voiceState != VoiceState.IDLE && it.isInputOrComposingActive }
                thenValue(
                    ImeHeaderVisibilityDecision(
                        showVoiceToggle = true,
                        showDictionaryButton = false,
                        showClipboardButton = false
                    )
                )
            }

            rule(
                id = "HEADER_R3",
                description = "During voice recording/decoding without active composing or key touch, keep all header controls visible"
            ) {
                `when` { it.voiceState != VoiceState.IDLE }
                thenValue(
                    ImeHeaderVisibilityDecision(
                        showVoiceToggle = true,
                        showDictionaryButton = true,
                        showClipboardButton = true
                    )
                )
            }

            rule(
                id = "HEADER_R4",
                description = "Hide mic, dictionary, and clipboard icons during flick/key touch or uncommitted composing to maximize candidate strip width"
            ) {
                `when` { it.isInputOrComposingActive }
                thenValue(
                    ImeHeaderVisibilityDecision(
                        showVoiceToggle = false,
                        showDictionaryButton = false,
                        showClipboardButton = false
                    )
                )
            }

            rule(
                id = "HEADER_R5",
                description = "Default idle state: show mic, dictionary, and clipboard icons"
            ) {
                `when` { true }
                thenValue(
                    ImeHeaderVisibilityDecision(
                        showVoiceToggle = true,
                        showDictionaryButton = true,
                        showClipboardButton = true
                    )
                )
            }
        }

    fun decideHeaderVisibility(
        input: ImeHeaderVisibilityInput
    ): ImeHeaderVisibilityDecision = headerVisibilityDecisionTable.evaluate(input)

    fun decideHeaderVisibilityWithTrace(
        input: ImeHeaderVisibilityInput
    ): DecisionTrace<ImeHeaderVisibilityDecision> =
        headerVisibilityDecisionTable.evaluateWithTrace(input)
}
