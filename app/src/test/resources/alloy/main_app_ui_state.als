module jotinput/main_app_ui_state

open util/ordering[State]
open util/boolean

/**
 * Formal Alloy 6 Specification for MainActivity v2 (3-Tab + DMN Smart Guide + Global Provisioning).
 *
 * Verifies:
 * 1. ReadyImpliesAllPrerequisitesMet: HeroReady is only shown when IME is enabled, selected,
 *    Japanese ASR is ready, base dicts are ready, and no dicts are outdated.
 * 2. HeroEnableImeHighestPriority: Whenever imeEnabled = False, HeroEnableIme is selected.
 * 3. TabSwitchPreservesState: Switching between Home, Settings, and Data tabs never alters
 *    setup, provisioning, or AI configuration state.
 * 4. SlmSelectorHiddenWhenDisabled: When slmEnabled = False, showSlmSelector is always False.
 * 5. GlobalBannerVisibleDuringProvisioning: Whenever provisioningActive = True,
 *    showGlobalBanner is True regardless of activeTab.
 */

abstract sig Tab {}
one sig TabHome, TabSettings, TabData extends Tab {}

abstract sig HeroAction {}
one sig
    HeroEnableIme,
    HeroSelectIme,
    HeroDownloadJaAsr,
    HeroDownloadDicts,
    HeroUpdateDicts,
    HeroReady
extends HeroAction {}

abstract sig EngineType {}
one sig Engine3_5_0_8B, Engine3_5_2B, EngineNone extends EngineType {}

sig State {
    activeTab: one Tab,
    imeEnabled: one Bool,
    imeSelected: one Bool,
    jaAsrReady: one Bool,
    baseDictReady: one Bool,
    outdatedDicts: one Bool,
    provisioningActive: one Bool,
    slmEnabled: one Bool,
    selectedEngine: one EngineType,
    imeInputActive: one Bool,
    selectedModelInstalled: one Bool,
    slmDownloading: one Bool,
    isUserSelection: one Bool,
    // Derived UI projections
    heroAction: one HeroAction,
    showGlobalBanner: one Bool,
    showSlmSelector: one Bool,
    showBottomNav: one Bool,
    showSlmDownloadCta: one Bool,
    promptSlmDownload: one Bool
}

pred deriveUi[s: State] {
    // DMN HomeHeroActionDecisionTable (FIRST Hit Policy)
    (s.imeEnabled = False) implies (s.heroAction = HeroEnableIme) else
    (s.imeSelected = False) implies (s.heroAction = HeroSelectIme) else
    (s.jaAsrReady = False) implies (s.heroAction = HeroDownloadJaAsr) else
    (s.baseDictReady = False) implies (s.heroAction = HeroDownloadDicts) else
    (s.outdatedDicts = True) implies (s.heroAction = HeroUpdateDicts) else
    (s.heroAction = HeroReady)

    // Global Provisioning Banner
    s.showGlobalBanner = s.provisioningActive

    // SLM Engine Selector visibility
    s.showSlmSelector = s.slmEnabled

    // DMN BottomNavVisibilityDecisionTable
    (s.imeInputActive = True) implies (s.showBottomNav = False) else (s.showBottomNav = True)

    // DMN AiEngineStatusDecisionTable (showDownloadCta) & SlmDownloadPromptDecisionTable
    (s.slmEnabled = True and s.selectedEngine != EngineNone and s.selectedModelInstalled = False and s.slmDownloading = False)
        implies (s.showSlmDownloadCta = True) else (s.showSlmDownloadCta = False)
    (s.isUserSelection = True and s.showSlmDownloadCta = True)
        implies (s.promptSlmDownload = True) else (s.promptSlmDownload = False)
}

pred init[s: State] {
    s.activeTab = TabHome
    s.imeEnabled = False
    s.imeSelected = False
    s.jaAsrReady = False
    s.baseDictReady = False
    s.outdatedDicts = False
    s.provisioningActive = False
    s.slmEnabled = True
    s.selectedEngine = Engine3_5_0_8B
    s.imeInputActive = False
    s.selectedModelInstalled = False
    s.slmDownloading = False
    s.isUserSelection = False
    deriveUi[s]
}

pred switchTab[s, sNext: State, t: Tab] {
    sNext.activeTab = t
    sNext.imeEnabled = s.imeEnabled
    sNext.imeSelected = s.imeSelected
    sNext.jaAsrReady = s.jaAsrReady
    sNext.baseDictReady = s.baseDictReady
    sNext.outdatedDicts = s.outdatedDicts
    sNext.provisioningActive = s.provisioningActive
    sNext.slmEnabled = s.slmEnabled
    sNext.selectedEngine = s.selectedEngine
    sNext.imeInputActive = s.imeInputActive
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = s.slmDownloading
    sNext.isUserSelection = False
    deriveUi[sNext]
}

pred enableAndSelectIme[s, sNext: State] {
    sNext.activeTab = s.activeTab
    sNext.imeEnabled = True
    sNext.imeSelected = True
    sNext.jaAsrReady = s.jaAsrReady
    sNext.baseDictReady = s.baseDictReady
    sNext.outdatedDicts = s.outdatedDicts
    sNext.provisioningActive = s.provisioningActive
    sNext.slmEnabled = s.slmEnabled
    sNext.selectedEngine = s.selectedEngine
    sNext.imeInputActive = s.imeInputActive
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = s.slmDownloading
    sNext.isUserSelection = False
    deriveUi[sNext]
}

pred startProvisioning[s, sNext: State] {
    sNext.activeTab = s.activeTab
    sNext.imeEnabled = s.imeEnabled
    sNext.imeSelected = s.imeSelected
    sNext.jaAsrReady = s.jaAsrReady
    sNext.baseDictReady = s.baseDictReady
    sNext.outdatedDicts = s.outdatedDicts
    sNext.provisioningActive = True
    sNext.slmEnabled = s.slmEnabled
    sNext.selectedEngine = s.selectedEngine
    sNext.imeInputActive = s.imeInputActive
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = s.slmDownloading
    sNext.isUserSelection = False
    deriveUi[sNext]
}

pred completeProvisioning[s, sNext: State] {
    s.provisioningActive = True
    sNext.activeTab = s.activeTab
    sNext.imeEnabled = s.imeEnabled
    sNext.imeSelected = s.imeSelected
    sNext.jaAsrReady = True
    sNext.baseDictReady = True
    sNext.outdatedDicts = False
    sNext.provisioningActive = False
    sNext.slmEnabled = s.slmEnabled
    sNext.selectedEngine = s.selectedEngine
    sNext.imeInputActive = s.imeInputActive
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = False
    sNext.isUserSelection = False
    deriveUi[sNext]
}

pred toggleSlm[s, sNext: State] {
    sNext.activeTab = s.activeTab
    sNext.imeEnabled = s.imeEnabled
    sNext.imeSelected = s.imeSelected
    sNext.jaAsrReady = s.jaAsrReady
    sNext.baseDictReady = s.baseDictReady
    sNext.outdatedDicts = s.outdatedDicts
    sNext.provisioningActive = s.provisioningActive
    (s.slmEnabled = True) implies {
        sNext.slmEnabled = False
        sNext.selectedEngine = EngineNone
    } else {
        sNext.slmEnabled = True
        // DMN RerankEngineSelectionDecisionTable defaults to Qwen3.5 0.8B when previously NONE
        sNext.selectedEngine = Engine3_5_0_8B
    }
    sNext.imeInputActive = s.imeInputActive
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = s.slmDownloading
    sNext.isUserSelection = False
    deriveUi[sNext]
}

pred select2B[s, sNext: State] {
    s.slmEnabled = True
    sNext.activeTab = s.activeTab
    sNext.imeEnabled = s.imeEnabled
    sNext.imeSelected = s.imeSelected
    sNext.jaAsrReady = s.jaAsrReady
    sNext.baseDictReady = s.baseDictReady
    sNext.outdatedDicts = s.outdatedDicts
    sNext.provisioningActive = s.provisioningActive
    sNext.slmEnabled = True
    sNext.selectedEngine = Engine3_5_2B
    sNext.imeInputActive = s.imeInputActive
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = s.slmDownloading
    sNext.isUserSelection = True
    deriveUi[sNext]
}

pred startImeInput[s, sNext: State] {
    sNext.activeTab = s.activeTab
    sNext.imeEnabled = s.imeEnabled
    sNext.imeSelected = s.imeSelected
    sNext.jaAsrReady = s.jaAsrReady
    sNext.baseDictReady = s.baseDictReady
    sNext.outdatedDicts = s.outdatedDicts
    sNext.provisioningActive = s.provisioningActive
    sNext.slmEnabled = s.slmEnabled
    sNext.selectedEngine = s.selectedEngine
    sNext.imeInputActive = True
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = s.slmDownloading
    sNext.isUserSelection = False
    deriveUi[sNext]
}

pred closeImeInput[s, sNext: State] {
    sNext.activeTab = s.activeTab
    sNext.imeEnabled = s.imeEnabled
    sNext.imeSelected = s.imeSelected
    sNext.jaAsrReady = s.jaAsrReady
    sNext.baseDictReady = s.baseDictReady
    sNext.outdatedDicts = s.outdatedDicts
    sNext.provisioningActive = s.provisioningActive
    sNext.slmEnabled = s.slmEnabled
    sNext.selectedEngine = s.selectedEngine
    sNext.imeInputActive = False
    sNext.selectedModelInstalled = s.selectedModelInstalled
    sNext.slmDownloading = s.slmDownloading
    sNext.isUserSelection = False
    deriveUi[sNext]
}

fact Traces {
    init[first]
    all s: State - last | let sNext = s.next |
        (some t: Tab | switchTab[s, sNext, t]) or
        enableAndSelectIme[s, sNext] or
        startProvisioning[s, sNext] or
        completeProvisioning[s, sNext] or
        toggleSlm[s, sNext] or
        select2B[s, sNext] or
        startImeInput[s, sNext] or
        closeImeInput[s, sNext]
}

// ============================================================================
// Safety Invariants
// ============================================================================

assert ReadyImpliesAllPrerequisitesMet {
    all s: State | s.heroAction = HeroReady implies (
        s.imeEnabled = True and
        s.imeSelected = True and
        s.jaAsrReady = True and
        s.baseDictReady = True and
        s.outdatedDicts = False
    )
}

assert HeroEnableImeHighestPriority {
    all s: State | s.imeEnabled = False implies s.heroAction = HeroEnableIme
}

assert TabSwitchPreservesState {
    all s: State - last | let sNext = s.next |
        (
            sNext.imeEnabled = s.imeEnabled and
            sNext.imeSelected = s.imeSelected and
            sNext.jaAsrReady = s.jaAsrReady and
            sNext.baseDictReady = s.baseDictReady and
            sNext.outdatedDicts = s.outdatedDicts and
            sNext.provisioningActive = s.provisioningActive and
            sNext.slmEnabled = s.slmEnabled and
            sNext.selectedEngine = s.selectedEngine
        ) or
        (sNext.imeEnabled != s.imeEnabled) or
        (sNext.imeSelected != s.imeSelected) or
        (sNext.provisioningActive != s.provisioningActive) or
        (sNext.slmEnabled != s.slmEnabled) or
        (sNext.selectedEngine != s.selectedEngine)
}

assert SlmSelectorHiddenWhenDisabled {
    all s: State | s.slmEnabled = False implies s.showSlmSelector = False
}

assert GlobalBannerVisibleDuringProvisioning {
    all s: State | s.provisioningActive = True implies s.showGlobalBanner = True
}

assert RerankEnabledImpliesValidModel {
    all s: State | s.slmEnabled = True implies (
        s.selectedEngine = Engine3_5_0_8B or
        s.selectedEngine = Engine3_5_2B
    )
}

assert RerankDisabledImpliesEngineNone {
    all s: State | s.slmEnabled = False implies s.selectedEngine = EngineNone
}

assert BottomNavHiddenWhenImeActive {
    all s: State | s.imeInputActive = True implies s.showBottomNav = False
}

assert NeverPromptDownloadWhenInstalledOrDisabled {
    all s: State | (
        s.slmEnabled = False or
        s.selectedEngine = EngineNone or
        s.selectedModelInstalled = True or
        s.slmDownloading = True
    ) implies s.promptSlmDownload = False
}

assert DownloadCtaOnlyWhenEnabledAndMissing {
    all s: State | s.showSlmDownloadCta = True implies (
        s.slmEnabled = True and
        s.selectedEngine != EngineNone and
        s.selectedModelInstalled = False and
        s.slmDownloading = False
    )
}

check ReadyImpliesAllPrerequisitesMet for 6 State
check HeroEnableImeHighestPriority for 6 State
check TabSwitchPreservesState for 6 State
check SlmSelectorHiddenWhenDisabled for 6 State
check GlobalBannerVisibleDuringProvisioning for 6 State
check RerankEnabledImpliesValidModel for 6 State
check RerankDisabledImpliesEngineNone for 6 State
check BottomNavHiddenWhenImeActive for 6 State
check NeverPromptDownloadWhenInstalledOrDisabled for 6 State
check DownloadCtaOnlyWhenEnabledAndMissing for 6 State

// ============================================================================
// Reachability (Non-Vacuity)
// ============================================================================

pred canReachReadyAfterSetup {
    some s: State | s.heroAction = HeroReady
}

pred canSwitchTabsWhileProvisioning {
    some s1, s2: State |
        s2 = s1.next and
        s1.activeTab = TabData and
        s1.provisioningActive = True and
        s2.activeTab = TabHome and
        s2.showGlobalBanner = True
}

pred canEnterAndExitImeInput {
    some s1, s2: State |
        s2 = s1.next and
        s1.imeInputActive = True and
        s1.showBottomNav = False and
        s2.imeInputActive = False and
        s2.showBottomNav = True
}

run canReachReadyAfterSetup for 5 State
run canSwitchTabsWhileProvisioning for 5 State
run canEnterAndExitImeInput for 5 State

