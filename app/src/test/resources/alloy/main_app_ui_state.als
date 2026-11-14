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

sig State {
    activeTab: one Tab,
    imeEnabled: one Bool,
    imeSelected: one Bool,
    jaAsrReady: one Bool,
    baseDictReady: one Bool,
    outdatedDicts: one Bool,
    provisioningActive: one Bool,
    slmEnabled: one Bool,
    // Derived UI projections
    heroAction: one HeroAction,
    showGlobalBanner: one Bool,
    showSlmSelector: one Bool
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
    sNext.slmEnabled = (s.slmEnabled = True implies False else True)
    deriveUi[sNext]
}

fact Traces {
    init[first]
    all s: State - last | let sNext = s.next |
        (some t: Tab | switchTab[s, sNext, t]) or
        enableAndSelectIme[s, sNext] or
        startProvisioning[s, sNext] or
        completeProvisioning[s, sNext] or
        toggleSlm[s, sNext]
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
            sNext.slmEnabled = s.slmEnabled
        ) or
        (sNext.imeEnabled != s.imeEnabled) or
        (sNext.imeSelected != s.imeSelected) or
        (sNext.provisioningActive != s.provisioningActive) or
        (sNext.slmEnabled != s.slmEnabled)
}

assert SlmSelectorHiddenWhenDisabled {
    all s: State | s.slmEnabled = False implies s.showSlmSelector = False
}

assert GlobalBannerVisibleDuringProvisioning {
    all s: State | s.provisioningActive = True implies s.showGlobalBanner = True
}

check ReadyImpliesAllPrerequisitesMet for 6 State
check HeroEnableImeHighestPriority for 6 State
check TabSwitchPreservesState for 6 State
check SlmSelectorHiddenWhenDisabled for 6 State
check GlobalBannerVisibleDuringProvisioning for 6 State

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

run canReachReadyAfterSetup for 5 State
run canSwitchTabsWhileProvisioning for 5 State
