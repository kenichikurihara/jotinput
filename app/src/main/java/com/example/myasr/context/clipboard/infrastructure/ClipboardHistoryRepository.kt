package com.example.myasr.context.clipboard.infrastructure

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Represents a single clipboard history entry.
 */
data class ClipboardHistoryItem(
    val id: String,
    val text: String,
    val discoveredAtMs: Long,
    val osClipTimestampMs: Long,
    val dismissedFromStrip: Boolean = false
)

/**
 * Volatile metadata for an OS-marked sensitive clip (e.g. password manager).
 * Never stores plaintext password and is never persisted to disk.
 */
data class SensitiveClipMetadata(
    val id: String,
    val discoveredAtMs: Long,
    val osClipTimestampMs: Long,
    val dismissedFromStrip: Boolean = false,
    val dismissedFromSheet: Boolean = false
)

sealed class RecentSuggestion {
    data class Regular(val item: ClipboardHistoryItem) : RecentSuggestion()
    data class Sensitive(val metadata: SensitiveClipMetadata) : RecentSuggestion()
}

/**
 * Manages clipboard observation, high-frequency polling, deduplication, 60-minute TTL expiration,
 * and the exception rule for the current OS clipboard content.
 */
class ClipboardHistoryRepository private constructor(private val appContext: Context) {

    private val TAG = "ClipboardHistoryRepo"

    companion object {
        const val HISTORY_TTL_MS = 60 * 60 * 1000L      // 60 minutes TTL for past clips
        const val CANDIDATE_SUGGEST_TTL_MS = 60 * 1000L // 1 minute (60s) suggestion on candidate strip
        const val SENSITIVE_VOLATILE_TTL_MS = 2 * 60 * 1000L // 2 minutes max volatile lifetime for sensitive clip
        const val MAX_HISTORY_ITEMS = 30
        const val MAX_TEXT_LENGTH = 10_000
        const val FAST_POLL_INTERVAL_MS = 500L          // 0.5s when keyboard is shown
        const val IDLE_POLL_INTERVAL_MS = 2000L         // 2.0s when screen is on but keyboard is idle

        @Volatile
        private var INSTANCE: ClipboardHistoryRepository? = null

        fun getInstance(context: Context): ClipboardHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipboardHistoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val clipboardManager: ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val powerManager: PowerManager? =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val items = ArrayList<ClipboardHistoryItem>()
    private val historyFile = File(appContext.filesDir, "clipboard_history.json")

    private var pollingJob: Job? = null
    private var isInputViewActive = false
    private var lastObservedTextHash: String? = null
    private var lastObservedClipTimestamp: Long = 0L

    // Volatile-only state for sensitive clips (never written to disk, no plaintext stored)
    private var volatileSensitiveClip: SensitiveClipMetadata? = null
    private var lastObservedSensitiveHash: String? = null
    private var lastObservedSensitiveTimestamp: Long = 0L

    var onHistoryChanged: ((hasNewClip: Boolean) -> Unit)? = null

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        scope.launch {
            syncWithOsClipboard(System.currentTimeMillis())
        }
    }

    init {
        scope.launch {
            mutex.withLock {
                loadFromDisk()
            }
        }
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register clipChangedListener: ${e.message}")
        }
        startPolling()
    }

    fun setInputViewActive(active: Boolean) {
        val changed = isInputViewActive != active
        isInputViewActive = active
        if (changed || active) {
            // Trigger immediate sync on input view show/hide or restart
            scope.launch {
                syncWithOsClipboard(System.currentTimeMillis())
            }
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                val isInteractive = powerManager?.isInteractive ?: true
                if (isInteractive) {
                    syncWithOsClipboard(System.currentTimeMillis())
                    val interval = if (isInputViewActive) FAST_POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS
                    delay(interval)
                } else {
                    // Screen is off: pause polling longer to save power
                    delay(5000L)
                }
            }
        }
    }

    /**
     * Reads OS primary clip and updates history accordingly.
     * Sensitive clips (EXTRA_IS_SENSITIVE) are tracked ONLY in volatile memory (hash + timestamp)
     * and are NEVER stored in `items` or written to disk.
     */
    suspend fun syncWithOsClipboard(nowMs: Long): Boolean = mutex.withLock {
        val currentDesc = try {
            clipboardManager?.primaryClipDescription
        } catch (e: Exception) {
            null
        }
        val osText = getRawOsPrimaryClipText()

        // Sensitive clip protection (e.g. password managers on Android 13+)
        if (isSensitiveClip(currentDesc)) {
            var sensitiveChanged = false
            var hasNewSensitiveClip = false

            if (!osText.isNullOrEmpty()) {
                val textToHash = if (osText.length > MAX_TEXT_LENGTH) osText.take(MAX_TEXT_LENGTH) else osText
                val textHash = computeHash(textToHash)
                val clipTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    currentDesc?.timestamp ?: nowMs
                } else {
                    nowMs
                }
                if (textHash != lastObservedSensitiveHash || clipTimestamp != lastObservedSensitiveTimestamp) {
                    lastObservedSensitiveHash = textHash
                    lastObservedSensitiveTimestamp = clipTimestamp
                    volatileSensitiveClip = SensitiveClipMetadata(
                        id = textHash,
                        discoveredAtMs = nowMs,
                        osClipTimestampMs = clipTimestamp,
                        dismissedFromStrip = false,
                        dismissedFromSheet = false
                    )
                    sensitiveChanged = true
                    hasNewSensitiveClip = true
                }
            }

            val currentSensitive = volatileSensitiveClip
            if (currentSensitive != null && (nowMs - currentSensitive.discoveredAtMs) > SENSITIVE_VOLATILE_TTL_MS) {
                volatileSensitiveClip = null
                sensitiveChanged = true
            }

            // Still purge expired regular items (never matching sensitive clip as exception)
            val regularPurged = purgeExpiredLocked(nowMs, null)
            if (regularPurged) {
                saveToDiskLocked()
            }

            if (sensitiveChanged || regularPurged) {
                scope.launch(Dispatchers.Main) {
                    onHistoryChanged?.invoke(hasNewSensitiveClip)
                }
            }
            return@withLock (sensitiveChanged || regularPurged)
        }

        var regularChanged = false
        var volatileCleared = false
        var hasNewClip = false

        // OS clipboard is no longer sensitive: immediately clear any volatile sensitive state
        if (volatileSensitiveClip != null || lastObservedSensitiveHash != null) {
            volatileSensitiveClip = null
            lastObservedSensitiveHash = null
            lastObservedSensitiveTimestamp = 0L
            volatileCleared = true
        }

        if (!osText.isNullOrBlank()) {
            val textToStore = if (osText.length > MAX_TEXT_LENGTH) osText.take(MAX_TEXT_LENGTH) else osText
            val textHash = computeHash(textToStore)
            val clipTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentDesc?.timestamp ?: nowMs
            } else {
                nowMs
            }

            // Check if this is a newly observed clip or re-copied
            if (textHash != lastObservedTextHash || clipTimestamp != lastObservedClipTimestamp) {
                lastObservedTextHash = textHash
                lastObservedClipTimestamp = clipTimestamp

                val existingIdx = items.indexOfFirst { it.text == textToStore }
                if (existingIdx >= 0) {
                    // Re-copied: move to top, bump discoveredAtMs, reset dismissedFromStrip
                    val existing = items.removeAt(existingIdx)
                    val updated = existing.copy(
                        discoveredAtMs = nowMs,
                        osClipTimestampMs = clipTimestamp,
                        dismissedFromStrip = false
                    )
                    items.add(0, updated)
                    regularChanged = true
                    hasNewClip = true
                } else {
                    // New clip discovered
                    val newItem = ClipboardHistoryItem(
                        id = textHash,
                        text = textToStore,
                        discoveredAtMs = nowMs,
                        osClipTimestampMs = clipTimestamp,
                        dismissedFromStrip = false
                    )
                    items.add(0, newItem)
                    regularChanged = true
                    hasNewClip = true
                }
            }
        }

        // Apply TTL and retention rule
        val purged = purgeExpiredLocked(nowMs, osText)
        if (purged) {
            regularChanged = true
        }

        // Cap to MAX_HISTORY_ITEMS
        while (items.size > MAX_HISTORY_ITEMS) {
            items.removeAt(items.size - 1)
            regularChanged = true
        }

        if (regularChanged) {
            saveToDiskLocked()
        }
        if (regularChanged || volatileCleared) {
            scope.launch(Dispatchers.Main) {
                onHistoryChanged?.invoke(hasNewClip)
            }
        }
        return@withLock (regularChanged || volatileCleared)
    }

    /**
     * Exception Rule:
     * - If item.text == currentOsClipText, retain indefinitely regardless of elapsed time.
     * - Otherwise, retain if nowMs - item.discoveredAtMs <= HISTORY_TTL_MS (60 min).
     */
    private fun purgeExpiredLocked(nowMs: Long, currentOsClipText: String?): Boolean {
        return items.removeAll { item ->
            !shouldRetain(item, nowMs, currentOsClipText)
        }
    }

    fun shouldRetain(item: ClipboardHistoryItem, nowMs: Long, currentOsClipText: String?): Boolean {
        if (!currentOsClipText.isNullOrEmpty() && item.text == currentOsClipText) {
            return true // Exception rule: OS primary clip is kept regardless of age
        }
        return (nowMs - item.discoveredAtMs) <= HISTORY_TTL_MS
    }

    /**
     * Returns candidate suggestion item if discovered within 60s and not dismissed from strip yet.
     */
    suspend fun getRecentSuggestionCandidate(nowMs: Long = System.currentTimeMillis()): ClipboardHistoryItem? = mutex.withLock {
        val latest = items.firstOrNull() ?: return@withLock null
        if (latest.dismissedFromStrip) return@withLock null
        if ((nowMs - latest.discoveredAtMs) <= CANDIDATE_SUGGEST_TTL_MS) {
            return@withLock latest
        }
        return@withLock null
    }

    /**
     * Returns either an eligible volatile sensitive clip or an eligible regular clip for the candidate strip,
     * prioritizing whichever was discovered more recently.
     */
    suspend fun getRecentSuggestionCandidateWithSensitivity(nowMs: Long = System.currentTimeMillis()): RecentSuggestion? = mutex.withLock {
        val sensitive = volatileSensitiveClip?.takeIf {
            !it.dismissedFromStrip &&
                !it.dismissedFromSheet &&
                (nowMs - it.discoveredAtMs) <= CANDIDATE_SUGGEST_TTL_MS &&
                (nowMs - it.discoveredAtMs) <= SENSITIVE_VOLATILE_TTL_MS
        }
        val regular = items.firstOrNull()?.takeIf {
            !it.dismissedFromStrip && (nowMs - it.discoveredAtMs) <= CANDIDATE_SUGGEST_TTL_MS
        }

        return@withLock when {
            sensitive != null && regular != null -> {
                if (sensitive.discoveredAtMs >= regular.discoveredAtMs) {
                    RecentSuggestion.Sensitive(sensitive)
                } else {
                    RecentSuggestion.Regular(regular)
                }
            }
            sensitive != null -> RecentSuggestion.Sensitive(sensitive)
            regular != null -> RecentSuggestion.Regular(regular)
            else -> null
        }
    }

    /**
     * Returns active volatile sensitive clip metadata for the clipboard sheet if still valid and not dismissed.
     */
    suspend fun getActiveSensitiveClipForSheet(nowMs: Long = System.currentTimeMillis()): SensitiveClipMetadata? = mutex.withLock {
        val current = volatileSensitiveClip ?: return@withLock null
        if (current.dismissedFromSheet || (nowMs - current.discoveredAtMs) > SENSITIVE_VOLATILE_TTL_MS) {
            if ((nowMs - current.discoveredAtMs) > SENSITIVE_VOLATILE_TTL_MS) {
                volatileSensitiveClip = null
            }
            return@withLock null
        }
        val currentDesc = try {
            clipboardManager?.primaryClipDescription
        } catch (e: Exception) {
            null
        }
        if (!isSensitiveClip(currentDesc)) {
            volatileSensitiveClip = null
            return@withLock null
        }
        return@withLock current
    }

    /**
     * Reads the OS primary clip on-demand for pasting a sensitive clip, verifying that it is still
     * sensitive and matches `expectedId`. Never persists or logs the returned text.
     */
    suspend fun consumeSensitiveClipTextForPaste(expectedId: String): String? = mutex.withLock {
        val currentDesc = try {
            clipboardManager?.primaryClipDescription
        } catch (e: Exception) {
            null
        }
        if (!isSensitiveClip(currentDesc)) {
            volatileSensitiveClip = null
            return@withLock null
        }
        val osText = getRawOsPrimaryClipText() ?: return@withLock null
        if (osText.isEmpty()) return@withLock null
        val textToHash = if (osText.length > MAX_TEXT_LENGTH) osText.take(MAX_TEXT_LENGTH) else osText
        if (computeHash(textToHash) != expectedId) {
            return@withLock null
        }
        volatileSensitiveClip = volatileSensitiveClip?.copy(dismissedFromStrip = true)
        return@withLock osText
    }

    suspend fun dismissSensitiveFromStrip(id: String) = mutex.withLock {
        val current = volatileSensitiveClip
        if (current != null && current.id == id) {
            volatileSensitiveClip = current.copy(dismissedFromStrip = true)
        }
    }

    suspend fun dismissSensitiveFromSheet(id: String) = mutex.withLock {
        val current = volatileSensitiveClip
        if (current != null && current.id == id) {
            volatileSensitiveClip = current.copy(dismissedFromStrip = true, dismissedFromSheet = true)
            scope.launch(Dispatchers.Main) {
                onHistoryChanged?.invoke(false)
            }
        }
    }

    /**
     * Mark an item as dismissed from candidate strip (e.g. after being pasted or user dismissed).
     */
    suspend fun markDismissedFromStrip(itemId: String) = mutex.withLock {
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            val item = items[idx]
            items[idx] = item.copy(dismissedFromStrip = true)
            saveToDiskLocked()
        }
    }

    /**
     * Returns active history list, purging any expired items.
     */
    suspend fun getActiveHistory(nowMs: Long = System.currentTimeMillis()): List<ClipboardHistoryItem> = mutex.withLock {
        val currentDesc = try {
            clipboardManager?.primaryClipDescription
        } catch (e: Exception) {
            null
        }
        val osText = if (isSensitiveClip(currentDesc)) null else getRawOsPrimaryClipText()
        purgeExpiredLocked(nowMs, osText)
        return@withLock ArrayList(items)
    }

    suspend fun deleteItem(id: String) = mutex.withLock {
        val removed = items.removeAll { it.id == id }
        if (removed) {
            saveToDiskLocked()
            scope.launch(Dispatchers.Main) {
                onHistoryChanged?.invoke(false)
            }
        }
    }

    suspend fun clearAll() = mutex.withLock {
        items.clear()
        volatileSensitiveClip = volatileSensitiveClip?.copy(dismissedFromStrip = true, dismissedFromSheet = true)
        saveToDiskLocked()
        scope.launch(Dispatchers.Main) {
            onHistoryChanged?.invoke(false)
        }
    }

    fun getCurrentOsClipText(): String? {
        return getRawOsPrimaryClipText()
    }

    private fun getRawOsPrimaryClipText(): String? {
        return try {
            val clip = clipboardManager?.primaryClip ?: return null
            if (clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                item.text?.toString() ?: item.coerceToText(appContext)?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error accessing clipboard manager: ${e.message}")
            null
        }
    }

    private fun isSensitiveClip(description: ClipDescription?): Boolean {
        if (description == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        } else {
            description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        }
    }

    private fun computeHash(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun loadFromDisk() {
        if (!historyFile.exists()) return
        try {
            val jsonStr = historyFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            val loaded = ArrayList<ClipboardHistoryItem>()
            val nowMs = System.currentTimeMillis()
            val osText = getRawOsPrimaryClipText()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val item = ClipboardHistoryItem(
                    id = obj.getString("id"),
                    text = obj.getString("text"),
                    discoveredAtMs = obj.getLong("discoveredAtMs"),
                    osClipTimestampMs = obj.optLong("osClipTimestampMs", 0L),
                    dismissedFromStrip = obj.optBoolean("dismissedFromStrip", false)
                )
                if (shouldRetain(item, nowMs, osText)) {
                    loaded.add(item)
                }
            }
            synchronized(items) {
                items.clear()
                items.addAll(loaded)
            }
            if (!osText.isNullOrBlank()) {
                val truncated = if (osText.length > MAX_TEXT_LENGTH) osText.take(MAX_TEXT_LENGTH) else osText
                val osHash = computeHash(truncated)
                val matchingLoaded = loaded.firstOrNull { it.id == osHash }
                if (matchingLoaded != null) {
                    val currentDesc = try {
                        clipboardManager?.primaryClipDescription
                    } catch (e: Exception) {
                        null
                    }
                    val currentOsTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        currentDesc?.timestamp ?: 0L
                    } else {
                        0L
                    }
                    if (matchingLoaded.osClipTimestampMs == currentOsTimestamp || currentOsTimestamp == 0L) {
                        lastObservedTextHash = matchingLoaded.id
                        lastObservedClipTimestamp = matchingLoaded.osClipTimestampMs
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load clipboard history from disk: ${e.message}")
        }
    }

    private fun saveToDiskLocked() {
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("discoveredAtMs", item.discoveredAtMs)
                    put("osClipTimestampMs", item.osClipTimestampMs)
                    put("dismissedFromStrip", item.dismissedFromStrip)
                }
                jsonArray.put(obj)
            }
            historyFile.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save clipboard history to disk: ${e.message}")
        }
    }
}
