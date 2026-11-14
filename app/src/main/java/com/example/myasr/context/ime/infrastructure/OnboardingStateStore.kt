package com.example.myasr.context.ime.infrastructure

import android.content.Context
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingMode
import com.example.myasr.context.ime.domain.OnboardingDecisionModel.OnboardingStep

/**
 * Persists onboarding progress and completion state across process death,
 * configuration changes, and external Settings jumps.
 */
class OnboardingStateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "onboarding_prefs"
        private const val KEY_COMPLETED = "onboarding_completed"
        private const val KEY_IN_PROGRESS_MODE = "onboarding_mode"
        private const val KEY_IN_PROGRESS_STEP = "onboarding_step"
    }

    fun isCompleted(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        prefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .remove(KEY_IN_PROGRESS_STEP)
            .apply()
    }

    fun saveProgress(mode: OnboardingMode, step: OnboardingStep) {
        prefs.edit()
            .putString(KEY_IN_PROGRESS_MODE, mode.name)
            .putString(KEY_IN_PROGRESS_STEP, step.name)
            .apply()
    }

    fun getSavedMode(): OnboardingMode? {
        val name = prefs.getString(KEY_IN_PROGRESS_MODE, null) ?: return null
        return runCatching { OnboardingMode.valueOf(name) }.getOrNull()
    }

    fun getSavedStep(): OnboardingStep? {
        val name = prefs.getString(KEY_IN_PROGRESS_STEP, null) ?: return null
        return runCatching { OnboardingStep.valueOf(name) }.getOrNull()
    }

    fun clearProgress() {
        prefs.edit()
            .remove(KEY_IN_PROGRESS_MODE)
            .remove(KEY_IN_PROGRESS_STEP)
            .apply()
    }
}
