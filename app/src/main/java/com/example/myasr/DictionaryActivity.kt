package com.example.myasr

import com.example.myasr.context.kanakanji.application.UserHistorySyncManager
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.DictionaryInputLogic
import com.example.myasr.context.vocabulary.domain.Priority
import com.example.myasr.context.vocabulary.infrastructure.DictionaryRepository
import com.example.myasr.context.vocabulary.presentation.DictionaryAdapter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DictionaryActivity : AppCompatActivity() {

    private lateinit var coordinatorRoot: CoordinatorLayout
    private lateinit var toolbar: MaterialToolbar

    // Quick Add Form
    private lateinit var layoutWord: TextInputLayout
    private lateinit var etWord: TextInputEditText
    private lateinit var tvEnglishAutoBadge: TextView
    private lateinit var layoutReading: TextInputLayout
    private lateinit var etReading: TextInputEditText
    private lateinit var chipPriorityNormal: Chip
    private lateinit var chipPriorityHigh: Chip
    private lateinit var btnAddWord: MaterialButton

    // Search & List
    private lateinit var etSearch: TextInputEditText
    private lateinit var tvRegisteredCount: TextView
    private lateinit var tvSyncStatusPill: TextView
    private lateinit var rvDictionary: RecyclerView
    private lateinit var layoutEmptyState: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var btnRegisterFromSearch: MaterialButton

    private lateinit var repository: DictionaryRepository
    private lateinit var adapter: DictionaryAdapter
    private lateinit var syncManager: UserHistorySyncManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private var lastAutoSuggestedReading: String? = null
    private var isUpdatingReadingProgrammatically = false
    private var currentSearchQuery: String = ""

    // Active sync bottom sheet references (if open)
    private var activeSyncDialog: BottomSheetDialog? = null
    private var activeSyncStatusSummary: TextView? = null
    private var activeHistoryStatsBadge: TextView? = null
    private var activeLayoutAutoSyncToggle: View? = null
    private var activeSwitchAutoSync: SwitchMaterial? = null
    private var activeBtnSyncGoogleDrive: MaterialButton? = null

    private val selectSyncFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            handleSyncFileSelected(uri)
        }
    }

    private val createSyncFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            handleSyncFileSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary)

        val app = AsrApp.instance
        repository = app.dictionaryRepository
        syncManager = UserHistorySyncManager.getInstance(this)

        initViews()
        setupRecyclerView()
        setupListeners()
        refreshList()
        updateSyncPillUI()
    }

    private fun initViews() {
        coordinatorRoot = findViewById(R.id.coordinatorRoot)
        toolbar = findViewById(R.id.toolbar)

        layoutWord = findViewById(R.id.layoutWord)
        etWord = findViewById(R.id.etWord)
        tvEnglishAutoBadge = findViewById(R.id.tvEnglishAutoBadge)
        layoutReading = findViewById(R.id.layoutReading)
        etReading = findViewById(R.id.etReading)
        chipPriorityNormal = findViewById(R.id.chipPriorityNormal)
        chipPriorityHigh = findViewById(R.id.chipPriorityHigh)
        btnAddWord = findViewById(R.id.btnAddWord)

        etSearch = findViewById(R.id.etSearch)
        tvRegisteredCount = findViewById(R.id.tvRegisteredCount)
        tvSyncStatusPill = findViewById(R.id.tvSyncStatusPill)
        rvDictionary = findViewById(R.id.rvDictionary)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle)
        btnRegisterFromSearch = findViewById(R.id.btnRegisterFromSearch)

        toolbar.setNavigationOnClickListener {
            finish()
        }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_history_sync) {
                showHistorySyncBottomSheet()
                true
            } else {
                false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = DictionaryAdapter(
            items = emptyList(),
            onItemClick = { entry ->
                showEditBottomSheet(entry)
            },
            onToggle = { entry ->
                repository.toggle(entry.id)
                updateHotwords()
                refreshList()
            },
            onDelete = { entry ->
                deleteEntryWithUndo(entry)
            }
        )
        rvDictionary.layoutManager = LinearLayoutManager(this)
        rvDictionary.adapter = adapter
    }

    private fun setupListeners() {
        etWord.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                layoutWord.error = null

                if (DictionaryInputLogic.isEnglishOnlyWordInput(text)) {
                    // 1-step English registration: hide Reading field and switch IME action to Done
                    layoutReading.visibility = View.GONE
                    layoutReading.error = null
                    tvEnglishAutoBadge.visibility = View.VISIBLE
                    etWord.imeOptions = EditorInfo.IME_ACTION_DONE
                } else {
                    layoutReading.visibility = View.VISIBLE
                    tvEnglishAutoBadge.visibility = View.GONE
                    etWord.imeOptions = EditorInfo.IME_ACTION_NEXT

                    // Smart auto-suggestion for Kana-only surface words (e.g. Katakana -> Hiragana)
                    val suggested = DictionaryInputLogic.suggestReadingIfKanaOnly(text)
                    val currentReading = etReading.text?.toString() ?: ""
                    if (currentReading.isEmpty() || currentReading == lastAutoSuggestedReading) {
                        isUpdatingReadingProgrammatically = true
                        etReading.setText(suggested ?: "")
                        isUpdatingReadingProgrammatically = false
                        lastAutoSuggestedReading = suggested
                    }
                }
            }
        })

        etReading.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingReadingProgrammatically) {
                    layoutReading.error = null
                    val current = s?.toString() ?: ""
                    if (current != lastAutoSuggestedReading) {
                        lastAutoSuggestedReading = null
                    }
                }
            }
        })

        etWord.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleAddWord()
                true
            } else {
                false
            }
        }

        etReading.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleAddWord()
                true
            } else {
                false
            }
        }

        btnAddWord.setOnClickListener {
            handleAddWord()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString() ?: ""
                refreshList()
            }
        })

        btnRegisterFromSearch.setOnClickListener {
            val query = currentSearchQuery.trim()
            if (query.isNotEmpty()) {
                etSearch.text?.clear()
                etWord.setText(query)
                etWord.setSelection(query.length)
                etWord.requestFocus()
            }
        }

        tvSyncStatusPill.setOnClickListener {
            showHistorySyncBottomSheet()
        }
    }

    private fun handleAddWord() {
        val rawWord = etWord.text?.toString() ?: ""
        val rawReading = if (layoutReading.visibility == View.VISIBLE) {
            etReading.text?.toString() ?: ""
        } else {
            ""
        }

        layoutWord.error = null
        layoutReading.error = null

        when (val result = DictionaryInputLogic.resolveWordAndReading(rawWord, rawReading)) {
            is DictionaryInputLogic.ResolutionResult.Error -> {
                when (result.field) {
                    DictionaryInputLogic.ErrorField.WORD -> {
                        layoutWord.error = result.message
                        etWord.requestFocus()
                    }
                    DictionaryInputLogic.ErrorField.READING -> {
                        layoutReading.visibility = View.VISIBLE
                        layoutReading.error = result.message
                        etReading.requestFocus()
                    }
                }
            }
            is DictionaryInputLogic.ResolutionResult.Success -> {
                val priority = if (chipPriorityHigh.isChecked) Priority.HIGH else Priority.NORMAL
                val entry = DictionaryEntry(
                    word = result.word,
                    reading = result.reading,
                    priority = priority,
                    isEnabled = true
                )
                val success = repository.add(entry)
                if (success) {
                    updateHotwords()
                    resetQuickAddForm()
                    refreshList()
                    rvDictionary.scrollToPosition(0)
                    Snackbar.make(
                        coordinatorRoot,
                        "「${result.word}」を登録しました",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetQuickAddForm() {
        lastAutoSuggestedReading = null
        isUpdatingReadingProgrammatically = true
        etWord.text?.clear()
        etReading.text?.clear()
        isUpdatingReadingProgrammatically = false
        chipPriorityNormal.isChecked = true
        layoutReading.visibility = View.VISIBLE
        tvEnglishAutoBadge.visibility = View.GONE
        layoutWord.error = null
        layoutReading.error = null
        etWord.imeOptions = EditorInfo.IME_ACTION_NEXT
    }

    private fun deleteEntryWithUndo(entry: DictionaryEntry) {
        val deleted = repository.delete(entry.id)
        if (!deleted) return

        updateHotwords()
        refreshList()

        Snackbar.make(coordinatorRoot, "「${entry.word}」を削除しました", Snackbar.LENGTH_LONG)
            .setAction("元に戻す") {
                repository.restore(entry)
                updateHotwords()
                refreshList()
            }
            .show()
    }

    private fun showEditBottomSheet(entry: DictionaryEntry) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_dictionary_edit, null)
        dialog.setContentView(view)

        val layoutEditWord = view.findViewById<TextInputLayout>(R.id.layoutEditWord)
        val etEditWord = view.findViewById<TextInputEditText>(R.id.etEditWord)
        val tvEditEnglishBadge = view.findViewById<TextView>(R.id.tvEditEnglishBadge)
        val layoutEditReading = view.findViewById<TextInputLayout>(R.id.layoutEditReading)
        val etEditReading = view.findViewById<TextInputEditText>(R.id.etEditReading)
        val chipEditNormal = view.findViewById<Chip>(R.id.chipEditNormal)
        val chipEditHigh = view.findViewById<Chip>(R.id.chipEditHigh)
        val btnEditDelete = view.findViewById<MaterialButton>(R.id.btnEditDelete)
        val btnEditSave = view.findViewById<MaterialButton>(R.id.btnEditSave)

        etEditWord.setText(entry.word)
        etEditWord.setSelection(entry.word.length)

        if (entry.isEnglishOnlyEntry) {
            layoutEditReading.visibility = View.GONE
            tvEditEnglishBadge.visibility = View.VISIBLE
        } else {
            layoutEditReading.visibility = View.VISIBLE
            tvEditEnglishBadge.visibility = View.GONE
            etEditReading.setText(entry.reading)
        }

        if (entry.priority == Priority.HIGH) {
            chipEditHigh.isChecked = true
        } else {
            chipEditNormal.isChecked = true
        }

        etEditWord.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                layoutEditWord.error = null
                if (DictionaryInputLogic.isEnglishOnlyWordInput(text)) {
                    layoutEditReading.visibility = View.GONE
                    layoutEditReading.error = null
                    tvEditEnglishBadge.visibility = View.VISIBLE
                } else {
                    layoutEditReading.visibility = View.VISIBLE
                    tvEditEnglishBadge.visibility = View.GONE
                }
            }
        })

        btnEditDelete.setOnClickListener {
            dialog.dismiss()
            deleteEntryWithUndo(entry)
        }

        btnEditSave.setOnClickListener {
            val rawWord = etEditWord.text?.toString() ?: ""
            val rawReading = if (layoutEditReading.visibility == View.VISIBLE) {
                etEditReading.text?.toString() ?: ""
            } else {
                ""
            }

            layoutEditWord.error = null
            layoutEditReading.error = null

            when (val result = DictionaryInputLogic.resolveWordAndReading(rawWord, rawReading)) {
                is DictionaryInputLogic.ResolutionResult.Error -> {
                    when (result.field) {
                        DictionaryInputLogic.ErrorField.WORD -> {
                            layoutEditWord.error = result.message
                            etEditWord.requestFocus()
                        }
                        DictionaryInputLogic.ErrorField.READING -> {
                            layoutEditReading.visibility = View.VISIBLE
                            layoutEditReading.error = result.message
                            etEditReading.requestFocus()
                        }
                    }
                }
                is DictionaryInputLogic.ResolutionResult.Success -> {
                    val priority = if (chipEditHigh.isChecked) Priority.HIGH else Priority.NORMAL
                    val updated = entry.copy(
                        word = result.word,
                        reading = result.reading,
                        priority = priority
                    )
                    if (repository.update(updated)) {
                        updateHotwords()
                        refreshList()
                        dialog.dismiss()
                        Snackbar.make(
                            coordinatorRoot,
                            "「${result.word}」を更新しました",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, "更新に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showHistorySyncBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_history_sync, null)
        dialog.setContentView(view)

        activeSyncDialog = dialog
        activeHistoryStatsBadge = view.findViewById(R.id.tvHistoryStatsBadge)
        activeSyncStatusSummary = view.findViewById(R.id.tvSyncStatusSummary)
        activeLayoutAutoSyncToggle = view.findViewById(R.id.layoutAutoSyncToggle)
        activeSwitchAutoSync = view.findViewById(R.id.switchAutoSync)
        activeBtnSyncGoogleDrive = view.findViewById(R.id.btnSyncGoogleDrive)
        val btnSelectSyncFile = view.findViewById<MaterialButton>(R.id.btnSelectSyncFile)
        val btnClearHistory = view.findViewById<MaterialButton>(R.id.btnClearHistory)

        activeBtnSyncGoogleDrive?.setOnClickListener {
            handleSyncButtonClick()
        }
        btnSelectSyncFile.setOnClickListener {
            promptChooseOrCreateSyncFile()
        }
        btnClearHistory.setOnClickListener {
            promptClearHistory()
        }
        activeSwitchAutoSync?.setOnCheckedChangeListener { _, isChecked ->
            syncManager.setAutoSyncEnabled(isChecked)
        }

        dialog.setOnDismissListener {
            activeSyncDialog = null
            activeHistoryStatsBadge = null
            activeSyncStatusSummary = null
            activeLayoutAutoSyncToggle = null
            activeSwitchAutoSync = null
            activeBtnSyncGoogleDrive = null
        }

        updateSyncSheetUI()
        dialog.show()
    }

    private fun updateSyncPillUI() {
        val stats = syncManager.getSyncStats()
        if (stats.syncUriString != null) {
            tvSyncStatusPill.text = "同期: 連携済"
            tvSyncStatusPill.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.badge_success_bg))
            tvSyncStatusPill.setTextColor(getColor(R.color.badge_success_text))
        } else {
            tvSyncStatusPill.text = "同期: 未連携"
            tvSyncStatusPill.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.badge_neutral_bg))
            tvSyncStatusPill.setTextColor(getColor(R.color.badge_neutral_text))
        }
    }

    private fun updateSyncSheetUI() {
        val stats = syncManager.getSyncStats()
        activeHistoryStatsBadge?.text = "単語 ${stats.unigramCount} / 連接 ${stats.bigramPairCount}"

        if (stats.syncUriString != null) {
            val dateStr = if (stats.lastSyncTimestamp > 0L) {
                val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                sdf.format(Date(stats.lastSyncTimestamp))
            } else {
                "未同期"
            }
            val summary = stats.lastSyncSummary ?: "準備完了"
            activeSyncStatusSummary?.text = "同期先設定済 • 最終同期: $dateStr ($summary)"
            activeBtnSyncGoogleDrive?.text = "Google Drive と同期 (安全マージ)"
            activeLayoutAutoSyncToggle?.visibility = View.VISIBLE
            activeSwitchAutoSync?.isChecked = stats.isAutoSyncEnabled
        } else {
            activeSyncStatusSummary?.text = "Google Driveの同期ファイルを選択すると、複数端末間で履歴を安全に統合できます。"
            activeBtnSyncGoogleDrive?.text = "同期先ファイルを選択して連携"
            activeLayoutAutoSyncToggle?.visibility = View.GONE
        }
    }

    private fun handleSyncButtonClick() {
        val currentUri = syncManager.getSyncUri()
        if (currentUri == null) {
            promptChooseOrCreateSyncFile()
        } else {
            startSyncExecution(currentUri)
        }
    }

    private fun promptChooseOrCreateSyncFile() {
        val options = arrayOf("Google Drive 上の既存ファイルを選択", "Google Drive 上に新規作成 (推奨)")
        AlertDialog.Builder(this)
            .setTitle("同期先ファイルの設定")
            .setItems(options) { _, which ->
                if (which == 0) {
                    selectSyncFileLauncher.launch(arrayOf("application/json", "*/*"))
                } else {
                    createSyncFileLauncher.launch("jotinput_history_sync.json")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun handleSyncFileSelected(uri: Uri) {
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            // Some document providers might not support persistable permissions
        }
        syncManager.setSyncUri(uri)
        updateSyncPillUI()
        updateSyncSheetUI()
        startSyncExecution(uri)
    }

    private fun startSyncExecution(uri: Uri) {
        activeBtnSyncGoogleDrive?.isEnabled = false
        activeBtnSyncGoogleDrive?.text = "同期処理中…"

        scope.launch {
            val result = syncManager.performSync(uri)
            activeBtnSyncGoogleDrive?.isEnabled = true
            updateSyncPillUI()
            updateSyncSheetUI()
            updateHotwords()
            refreshList()

            Toast.makeText(this@DictionaryActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("学習履歴のクリア")
            .setMessage("変換候補の単語選択履歴および次フレーズ予測の接続履歴をリセットしますか？\n（※登録済み単語は削除されません）")
            .setPositiveButton("クリア") { _, _ ->
                val app = AsrApp.instance
                app.kanaKanjiEngine.clearHistory()
                app.nextPhrasePredictor.clearHistory()
                updateSyncSheetUI()
                Toast.makeText(this, "学習履歴をクリアしました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateHotwords() {
        AsrApp.instanceOrNull?.syncAllUserDictionaries()
    }

    private fun refreshList() {
        val allEntries = repository.getAll()
        val activeCount = allEntries.count { it.isEnabled }
        val filtered = DictionaryInputLogic.filterEntries(allEntries, currentSearchQuery)

        adapter.updateData(filtered)
        tvRegisteredCount.text = "登録済み: ${allEntries.size}件 (有効: ${activeCount}件)"

        if (filtered.isEmpty()) {
            rvDictionary.visibility = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
            val trimmedQuery = currentSearchQuery.trim()
            if (trimmedQuery.isNotEmpty()) {
                tvEmptyTitle.text = "「$trimmedQuery」に一致する単語はありません"
                tvEmptySubtitle.text = "下のボタンから新しい単語として登録できます。"
                btnRegisterFromSearch.text = "「$trimmedQuery」を新しく登録"
                btnRegisterFromSearch.visibility = View.VISIBLE
            } else {
                tvEmptyTitle.text = "登録された単語はありません"
                tvEmptySubtitle.text = "よく使う固有名詞や英単語を上のフォームから追加すると、音声入力と変換の両方で最優先されます。"
                btnRegisterFromSearch.visibility = View.GONE
            }
        } else {
            rvDictionary.visibility = View.VISIBLE
            layoutEmptyState.visibility = View.GONE
        }
    }
}
