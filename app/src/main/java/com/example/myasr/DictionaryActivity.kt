package com.example.myasr

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DictionaryActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var nestedScrollView: NestedScrollView
    private lateinit var tvFormTitle: TextView
    private lateinit var tvFormSubtitle: TextView
    private lateinit var layoutWord: TextInputLayout
    private lateinit var etWord: TextInputEditText
    private lateinit var layoutReading: TextInputLayout
    private lateinit var etReading: TextInputEditText
    private lateinit var rgPriority: RadioGroup
    private lateinit var rbNormal: RadioButton
    private lateinit var rbHigh: RadioButton
    private lateinit var btnCancelEdit: MaterialButton
    private lateinit var btnAddWord: MaterialButton
    private lateinit var tvRegisteredCount: TextView
    private lateinit var tvActiveHotwords: TextView
    private lateinit var rvDictionary: RecyclerView

    private lateinit var repository: DictionaryRepository
    private lateinit var hotwordsGenerator: HotwordsGenerator
    private lateinit var adapter: DictionaryAdapter

    private var editingEntry: DictionaryEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary)

        val app = AsrApp.instance
        repository = app.dictionaryRepository
        hotwordsGenerator = app.hotwordsGenerator

        initViews()
        setupRecyclerView()
        setupListeners()
        refreshList()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        nestedScrollView = findViewById(R.id.nestedScrollView)
        tvFormTitle = findViewById(R.id.tvFormTitle)
        tvFormSubtitle = findViewById(R.id.tvFormSubtitle)
        layoutWord = findViewById(R.id.layoutWord)
        etWord = findViewById(R.id.etWord)
        layoutReading = findViewById(R.id.layoutReading)
        etReading = findViewById(R.id.etReading)
        rgPriority = findViewById(R.id.rgPriority)
        rbNormal = findViewById(R.id.rbNormal)
        rbHigh = findViewById(R.id.rbHigh)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        btnAddWord = findViewById(R.id.btnAddWord)
        tvRegisteredCount = findViewById(R.id.tvRegisteredCount)
        tvActiveHotwords = findViewById(R.id.tvActiveHotwords)
        rvDictionary = findViewById(R.id.rvDictionary)

        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = DictionaryAdapter(
            items = emptyList(),
            onItemClick = { entry ->
                startEditing(entry)
            },
            onToggle = { entry ->
                repository.toggle(entry.id)
                updateHotwords()
                refreshList()
            },
            onDelete = { entry ->
                if (editingEntry?.id == entry.id) {
                    cancelEditing()
                }
                repository.delete(entry.id)
                updateHotwords()
                refreshList()
                Toast.makeText(this, "「${entry.word}」を削除しました", Toast.LENGTH_SHORT).show()
            }
        )
        rvDictionary.layoutManager = LinearLayoutManager(this)
        rvDictionary.adapter = adapter
    }

    private fun setupListeners() {
        etReading.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (EnglishDictionaryUtils.isValidEnglishWord(text)) {
                    layoutWord.visibility = View.GONE
                    layoutWord.error = null
                    tvFormSubtitle.text = "英語は「よみ」の入力のみで登録できます（書きの入力は不要です）。"
                } else {
                    layoutWord.visibility = View.VISIBLE
                    tvFormSubtitle.text = "日本語（表記と読み）または英語（読みのみ）を登録して最優先で反映させます。"
                }
            }
        })

        btnAddWord.setOnClickListener {
            handleAddOrUpdateWord()
        }

        btnCancelEdit.setOnClickListener {
            cancelEditing()
        }
    }

    private fun startEditing(entry: DictionaryEntry) {
        editingEntry = entry
        tvFormTitle.text = "単語の編集"

        if (entry.isEnglishOnlyEntry) {
            tvFormSubtitle.text = "「${entry.word}」の優先度やスペルを変更できます（書きの入力は不要です）。"
            etReading.setText(entry.word)
            etReading.setSelection(entry.word.length)
            etWord.text?.clear()
            layoutWord.visibility = View.GONE
        } else {
            tvFormSubtitle.text = "「${entry.word}」の表記、読み、優先度を変更できます。"
            etReading.setText(entry.reading)
            etReading.setSelection(entry.reading.length)
            etWord.setText(entry.word)
            etWord.setSelection(entry.word.length)
            layoutWord.visibility = View.VISIBLE
        }

        if (entry.priority == Priority.HIGH) {
            rbHigh.isChecked = true
        } else {
            rbNormal.isChecked = true
        }

        btnAddWord.text = "単語を更新"
        btnCancelEdit.visibility = View.VISIBLE

        layoutWord.error = null
        layoutReading.error = null

        // Smooth scroll to top and focus reading input
        nestedScrollView.smoothScrollTo(0, 0)
        etReading.requestFocus()

        Toast.makeText(this, "「${entry.word}」を編集フォームに読み込みました", Toast.LENGTH_SHORT).show()
    }

    private fun cancelEditing() {
        editingEntry = null
        tvFormTitle.text = "単語の追加"
        tvFormSubtitle.text = "日本語（表記と読み）または英語（読みのみ）を登録して最優先で反映させます。"

        etWord.text?.clear()
        etReading.text?.clear()
        rbNormal.isChecked = true
        layoutWord.visibility = View.VISIBLE

        btnAddWord.text = "辞書に追加"
        btnCancelEdit.visibility = View.GONE

        layoutWord.error = null
        layoutReading.error = null
        layoutWord.clearFocus()
        layoutReading.clearFocus()
    }

    private fun handleAddOrUpdateWord() {
        val rawWord = etWord.text?.toString()?.trim() ?: ""
        val rawReading = etReading.text?.toString()?.trim() ?: ""

        layoutWord.error = null
        layoutReading.error = null

        val finalWord: String
        val finalReading: String

        if (rawReading.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(rawReading)) {
            // English mode (Reading only, Word not required)
            finalWord = if (rawWord.isNotEmpty()) rawWord else rawReading
            finalReading = EnglishDictionaryUtils.normalizeEnglishReading(rawReading)
        } else if (rawReading.isEmpty() && rawWord.isNotEmpty() && EnglishDictionaryUtils.isValidEnglishWord(rawWord)) {
            // Fallback English mode: user typed English word into etWord
            finalWord = rawWord
            finalReading = EnglishDictionaryUtils.normalizeEnglishReading(rawWord)
        } else {
            // Japanese mode: both required, reading must be kana
            if (rawWord.isEmpty()) {
                layoutWord.error = "単語を入力してください (Word cannot be empty)"
                etWord.requestFocus()
                return
            }
            if (rawReading.isEmpty()) {
                layoutReading.error = "よみを入力してください (Reading cannot be empty)"
                etReading.requestFocus()
                return
            }
            if (!JapaneseKanaUtils.isValidKana(rawReading)) {
                layoutReading.error = "よみはひらがな・カタカナ、または英単語で入力してください"
                etReading.requestFocus()
                return
            }
            finalWord = rawWord
            finalReading = JapaneseKanaUtils.normalizeReading(rawReading)
        }

        val priority = if (rbHigh.isChecked) Priority.HIGH else Priority.NORMAL

        val currentEditing = editingEntry
        if (currentEditing != null) {
            // Update mode
            val updated = currentEditing.copy(
                word = finalWord,
                reading = finalReading,
                priority = priority
            )
            val success = repository.update(updated)
            if (success) {
                updateHotwords()
                cancelEditing()
                refreshList()
                Toast.makeText(this, "「$finalWord」を更新しました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "更新に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Add mode
            val entry = DictionaryEntry(
                word = finalWord,
                reading = finalReading,
                priority = priority,
                isEnabled = true
            )
            val success = repository.add(entry)
            if (success) {
                updateHotwords()
                cancelEditing()
                refreshList()
                Toast.makeText(this, "「$finalWord」を登録しました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateHotwords() {
        AsrApp.instanceOrNull?.syncAllUserDictionaries()
    }

    private fun refreshList() {
        val allEntries = repository.getAll()
        val activeCount = allEntries.count { it.isEnabled }
        adapter.updateData(allEntries)

        tvRegisteredCount.text = "登録済み単語 (${allEntries.size}件)"
        tvActiveHotwords.text = "有効: ${activeCount}件"
    }
}
