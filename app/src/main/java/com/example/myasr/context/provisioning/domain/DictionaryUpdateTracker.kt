package com.example.myasr.context.provisioning.domain

import com.example.myasr.AsrApp
import com.example.myasr.MainActivity
import com.example.myasr.context.kanakanji.domain.KanaKanjiEngine
import com.example.myasr.context.provisioning.infrastructure.DictionaryType
import com.example.myasr.context.provisioning.infrastructure.MultiDictionaryManager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class DictUpdateState {
    NOT_INSTALLED,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    INSTALLED_REMOTE_UNKNOWN
}

data class DictionaryStatusInfo(
    val sourceId: String,
    val displayName: String,
    val isInstalled: Boolean,
    val wordCount: Int,
    val localUpdatedAtMillis: Long,
    val remoteUpdatedAtMillis: Long,
    val state: DictUpdateState
) {
    fun formatLocalDate(): String {
        if (!isInstalled || localUpdatedAtMillis <= 0L) return "未導入"
        return DictionaryUpdateTracker.formatDateMillis(localUpdatedAtMillis, "未導入")
    }

    fun formatRemoteDate(): String {
        if (remoteUpdatedAtMillis <= 0L) return "未確認"
        return DictionaryUpdateTracker.formatDateMillis(remoteUpdatedAtMillis, "未確認")
    }

    fun formatComparisonLine(): String {
        val localStr = formatLocalDate()
        val remoteStr = formatRemoteDate()
        val suffix = when (state) {
            DictUpdateState.UPDATE_AVAILABLE -> " (要更新)"
            DictUpdateState.UP_TO_DATE -> " (最新)"
            else -> ""
        }
        return "端末更新: $localStr  ｜  ネット最新: $remoteStr$suffix"
    }
}

class DictionaryUpdateTracker(private val context: Context) {

    companion object {
        private const val TAG = "DictUpdateTracker"
        private const val PREFS_NAME = "dictionary_update_metadata"
        private const val KEY_LAST_CHECK_MS = "last_remote_check_ms"
        private const val KEY_LAST_NOTIFIED_REMOTE_MS = "last_notified_remote_ms"
        private const val PREFIX_LOCAL_UPDATED_MS = "local_updated_ms_"
        private const val PREFIX_REMOTE_UPDATED_MS = "remote_updated_ms_"

        const val BASE_DICT_ID = "base_dictionary"
        const val BASE_DICT_DISPLAY_NAME = "Mozc 基本・標準語彙 (コア辞書)"

        // Auto-check interval: 6 hours
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

        private const val NOTIFICATION_CHANNEL_ID = "dictionary_update_channel"
        private const val NOTIFICATION_ID = 4102

        private val ATOM_UPDATED_REGEX = Regex("""<updated>\s*([^<\s]+)\s*</updated>""")
        private val GIST_UPDATED_AT_REGEX = Regex(""""updated_at"\s*:\s*"([^"]+)"""")

        fun getRemoteCheckEndpoint(sourceId: String): String? {
            return when (sourceId) {
                BASE_DICT_ID -> "https://api.github.com/gists/39665c6e7ba6e63d6fe86c4c4b1bbd42"
                DictionaryType.MOZC_GENERAL.id -> "https://github.com/google/mozc/commits/master/src/data/dictionary_oss.atom"
                DictionaryType.SUDACHIDICT.id -> "https://github.com/utuhiro78/mozcdic-ut-sudachidict/commits/main.atom"
                DictionaryType.JAWIKI.id -> "https://github.com/utuhiro78/mozcdic-ut-jawiki/commits/main.atom"
                DictionaryType.PLACE_NAMES.id -> "https://github.com/utuhiro78/mozcdic-ut-place-names/commits/main.atom"
                DictionaryType.PERSONAL_NAMES.id -> "https://github.com/utuhiro78/mozcdic-ut-personal-names/commits/main.atom"
                DictionaryType.EMOJI_SYMBOLS.id -> "https://github.com/google/mozc/commits/master/src/data/emoji.atom"
                else -> null
            }
        }

        fun getAllSourceIds(): List<String> {
            val list = mutableListOf(BASE_DICT_ID)
            DictionaryType.values().forEach { list.add(it.id) }
            return list
        }

        fun parseAtomFeedUpdatedMillis(xmlContent: String): Long {
            val match = ATOM_UPDATED_REGEX.find(xmlContent) ?: return 0L
            return parseIso8601ToMillis(match.groupValues[1])
        }

        fun parseGistUpdatedMillis(jsonContent: String): Long {
            val match = GIST_UPDATED_AT_REGEX.find(jsonContent) ?: return 0L
            return parseIso8601ToMillis(match.groupValues[1])
        }

        fun parseIso8601ToMillis(isoString: String): Long {
            val trimmed = isoString.trim()
            if (trimmed.isEmpty()) return 0L

            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            )
            for (pattern in patterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US)
                    if (pattern.endsWith("'Z'")) {
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val parsed = sdf.parse(trimmed)
                    if (parsed != null && parsed.time > 0L) {
                        return parsed.time
                    }
                } catch (_: Exception) {
                }
            }
            return 0L
        }

        fun evaluateUpdateState(
            isInstalled: Boolean,
            localUpdatedAtMillis: Long,
            remoteUpdatedAtMillis: Long
        ): DictUpdateState {
            if (!isInstalled) return DictUpdateState.NOT_INSTALLED
            if (remoteUpdatedAtMillis <= 0L) return DictUpdateState.INSTALLED_REMOTE_UNKNOWN
            return if (remoteUpdatedAtMillis > localUpdatedAtMillis) {
                DictUpdateState.UPDATE_AVAILABLE
            } else {
                DictUpdateState.UP_TO_DATE
            }
        }

        fun formatDateMillis(
            millis: Long,
            fallback: String = "未確認",
            timeZone: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")
        ): String {
            if (millis <= 0L) return fallback
            val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.US)
            sdf.timeZone = timeZone
            return sdf.format(Date(millis))
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records that a dictionary was freshly downloaded/updated on the device.
     */
    fun markDictionaryUpdated(sourceId: String, timestampMillis: Long = System.currentTimeMillis()) {
        val remoteMillis = getRemoteUpdatedAtMillis(sourceId)
        val effectiveMillis = maxOf(timestampMillis, remoteMillis)
        prefs.edit()
            .putLong(PREFIX_LOCAL_UPDATED_MS + sourceId, effectiveMillis)
            .apply()
        Log.i(TAG, "Marked dictionary '$sourceId' updated at ${formatDateMillis(effectiveMillis)}")
    }

    /**
     * Clears local update timestamp when a dictionary is deleted.
     */
    fun clearLocalUpdatedAt(sourceId: String) {
        prefs.edit()
            .remove(PREFIX_LOCAL_UPDATED_MS + sourceId)
            .apply()
    }

    /**
     * Returns the local installation/update timestamp for a dictionary.
     * If the dictionary is already installed on disk from before this tracker existed,
     * falls back to the file's lastModified() timestamp and persists it.
     */
    fun getLocalUpdatedAtMillis(sourceId: String, isInstalled: Boolean): Long {
        if (!isInstalled) return 0L
        val stored = prefs.getLong(PREFIX_LOCAL_UPDATED_MS + sourceId, 0L)
        if (stored > 0L) return stored

        val fallbackFileMillis = when (sourceId) {
            BASE_DICT_ID, DictionaryType.MOZC_GENERAL.id -> {
                val f1 = java.io.File(KanaKanjiEngine.getInstance(context).getMozcDir(), "dict_mozc_general.bin")
                if (f1.exists() && f1.length() > 0L) f1.lastModified()
                else {
                    val f2 = KanaKanjiEngine.getInstance(context).getSystemDictionaryBinFile()
                    if (f2.exists() && f2.length() > 0L) f2.lastModified()
                    else {
                        val f3 = KanaKanjiEngine.getInstance(context).getBaseDictionaryFile()
                        if (f3.exists() && f3.length() > 0L) f3.lastModified() else 0L
                    }
                }
            }
            else -> {
                val type = DictionaryType.fromId(sourceId)
                if (type != null) {
                    val dictFile = (AsrApp.instanceOrNull?.multiDictionaryManager ?: MultiDictionaryManager(context)).getDictFile(type)
                    if (dictFile.exists() && dictFile.length() > 0L) dictFile.lastModified() else 0L
                } else 0L
            }
        }
        val resolved = if (fallbackFileMillis > 0L) fallbackFileMillis else System.currentTimeMillis()
        prefs.edit().putLong(PREFIX_LOCAL_UPDATED_MS + sourceId, resolved).apply()
        return resolved
    }

    fun getRemoteUpdatedAtMillis(sourceId: String): Long {
        return prefs.getLong(PREFIX_REMOTE_UPDATED_MS + sourceId, 0L)
    }

    fun setRemoteUpdatedAtMillis(sourceId: String, remoteMillis: Long) {
        if (remoteMillis > 0L) {
            prefs.edit().putLong(PREFIX_REMOTE_UPDATED_MS + sourceId, remoteMillis).apply()
        }
    }

    fun getLastCheckTimestampMillis(): Long {
        return prefs.getLong(KEY_LAST_CHECK_MS, 0L)
    }

    /**
     * Builds the current status snapshot for a single dictionary source.
     */
    fun getStatusInfo(sourceId: String): DictionaryStatusInfo {
        val manager = AsrApp.instanceOrNull?.multiDictionaryManager ?: MultiDictionaryManager(context)

        val isInstalled: Boolean
        val wordCount: Int
        val displayName: String

        val type = if (sourceId == BASE_DICT_ID) DictionaryType.MOZC_GENERAL else DictionaryType.fromId(sourceId)
        wordCount = if (type != null) manager.getWordCount(type) else 0
        isInstalled = wordCount > 0
        displayName = type?.displayName ?: sourceId

        val localMs = getLocalUpdatedAtMillis(sourceId, isInstalled)
        val remoteMs = getRemoteUpdatedAtMillis(sourceId)
        val state = evaluateUpdateState(isInstalled, localMs, remoteMs)

        return DictionaryStatusInfo(
            sourceId = sourceId,
            displayName = displayName,
            isInstalled = isInstalled,
            wordCount = wordCount,
            localUpdatedAtMillis = localMs,
            remoteUpdatedAtMillis = remoteMs,
            state = state
        )
    }

    /**
     * Returns status snapshots for all 7 dictionaries (Base + 6 Extended).
     */
    fun getAllStatusInfo(): List<DictionaryStatusInfo> {
        return getAllSourceIds().map { getStatusInfo(it) }
    }

    /**
     * Returns whether an automatic remote check should run now.
     */
    fun shouldAutoCheck(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val lastCheck = getLastCheckTimestampMillis()
        if (lastCheck <= 0L || (nowMillis - lastCheck) >= AUTO_CHECK_INTERVAL_MS) {
            return true
        }
        // Also check if any source is still missing its remote timestamp
        return getAllSourceIds().any { getRemoteUpdatedAtMillis(it) <= 0L }
    }

    /**
     * Asynchronously fetches the latest upstream update dates for all dictionaries from GitHub / Gist.
     * Posts an Android notification if new updates are discovered for installed dictionaries.
     */
    suspend fun checkRemoteUpdates(
        force: Boolean = false,
        postSystemNotification: Boolean = true
    ): List<DictionaryStatusInfo> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && !shouldAutoCheck(now)) {
            return@withContext getAllStatusInfo()
        }

        val sourceIds = getAllSourceIds()
        var anyFetched = false

        coroutineScope {
            val jobs = sourceIds.map { sourceId ->
                async {
                    val endpoint = getRemoteCheckEndpoint(sourceId) ?: return@async
                    val remoteMillis = fetchRemoteTimestamp(sourceId, endpoint)
                    if (remoteMillis > 0L) {
                        setRemoteUpdatedAtMillis(sourceId, remoteMillis)
                        anyFetched = true
                    }
                }
            }
            jobs.awaitAll()
        }

        if (anyFetched) {
            prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
        }

        val statuses = getAllStatusInfo()
        if (postSystemNotification) {
            notifyIfNewUpdatesAvailable(statuses)
        }
        statuses
    }

    private fun fetchRemoteTimestamp(sourceId: String, urlStr: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "JotInput-Android/1.0")
                if (sourceId == BASE_DICT_ID) {
                    setRequestProperty("Accept", "application/vnd.github+json")
                } else {
                    setRequestProperty("Accept", "application/atom+xml, application/xml, text/xml")
                }
            }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $code when checking remote update for $sourceId ($urlStr)")
                return 0L
            }

            val content = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                var linesRead = 0
                // We only need the first ~40 lines of the Atom feed or Gist JSON to find the timestamp
                while (reader.readLine().also { line = it } != null && linesRead < 60) {
                    content.append(line).append('\n')
                    linesRead++
                    if (sourceId != BASE_DICT_ID && line!!.contains("</updated>")) {
                        break
                    }
                }
            }

            val body = content.toString()
            val millis = if (sourceId == BASE_DICT_ID) {
                parseGistUpdatedMillis(body)
            } else {
                parseAtomFeedUpdatedMillis(body)
            }
            Log.d(TAG, "Fetched remote date for $sourceId: ${formatDateMillis(millis)} ($millis)")
            millis
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch remote timestamp for $sourceId: ${e.message}")
            0L
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyIfNewUpdatesAvailable(statuses: List<DictionaryStatusInfo>) {
        val outdatedList = statuses.filter { it.state == DictUpdateState.UPDATE_AVAILABLE }
        if (outdatedList.isEmpty()) return

        val maxRemoteMillis = outdatedList.maxOfOrNull { it.remoteUpdatedAtMillis } ?: 0L
        val lastNotifiedMillis = prefs.getLong(KEY_LAST_NOTIFIED_REMOTE_MS, 0L)
        if (maxRemoteMillis <= lastNotifiedMillis) {
            return // Already notified the user for this wave of dictionary updates
        }

        // Record that we processed notification for this remote update timestamp
        prefs.edit().putLong(KEY_LAST_NOTIFIED_REMOTE_MS, maxRemoteMillis).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "POST_NOTIFICATIONS not granted; relying on in-app dictionary update banner.")
                return
            }
        }

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "辞書アップデート通知",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "ネット上の変換辞書に新しい更新が公開された際に通知します"
                }
                nm.createNotificationChannel(channel)
            }

            val namesPreview = outdatedList.take(2).joinToString("・") { it.displayName.substringBefore(" ") }
            val extraCount = (outdatedList.size - 2).coerceAtLeast(0)
            val namesSummary = if (extraCount > 0) "${namesPreview} ほか${extraCount}件" else namesPreview
            val latestDateStr = formatDateMillis(maxRemoteMillis)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, pendingIntentFlags)

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("変換辞書の更新があります (${outdatedList.size}件)")
                .setContentText("$namesSummary に最新版 ($latestDateStr) が公開されています。タップして更新できます。")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$namesSummary に最新版 ($latestDateStr) が公開されています。JotInputを開いてワンタップで最新語彙へ更新できます。"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "Posted system notification for ${outdatedList.size} outdated dictionaries.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not post dictionary update notification: ${e.message}")
        }
    }
}
