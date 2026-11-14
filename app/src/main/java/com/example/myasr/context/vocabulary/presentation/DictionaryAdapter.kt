package com.example.myasr.context.vocabulary.presentation

import com.example.myasr.R
import com.example.myasr.context.kanakanji.domain.JapaneseKanaUtils
import com.example.myasr.context.vocabulary.domain.DictionaryEntry
import com.example.myasr.context.vocabulary.domain.Priority

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial

class DictionaryAdapter(
    private var items: List<DictionaryEntry>,
    private val onItemClick: (DictionaryEntry) -> Unit,
    private val onToggle: (DictionaryEntry) -> Unit,
    private val onDelete: (DictionaryEntry) -> Unit
) : RecyclerView.Adapter<DictionaryAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvWord: TextView = itemView.findViewById(R.id.tvWord)
        val tvPriorityBadge: TextView = itemView.findViewById(R.id.tvPriorityBadge)
        val tvReading: TextView = itemView.findViewById(R.id.tvReading)
        val switchEnabled: SwitchMaterial = itemView.findViewById(R.id.switchEnabled)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dictionary_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]

        holder.itemView.setOnClickListener {
            onItemClick(entry)
        }

        holder.tvWord.text = entry.word
        holder.tvPriorityBadge.visibility = if (entry.priority == Priority.HIGH) View.VISIBLE else View.GONE

        if (entry.isEnglishOnlyEntry) {
            holder.tvReading.text = "英語 (よみ不要)"
        } else {
            val katakana = JapaneseKanaUtils.hiraganaToKatakana(entry.reading)
            holder.tvReading.text = "${entry.reading} ($katakana)"
        }

        val contentAlpha = if (entry.isEnabled) 1.0f else 0.45f
        holder.tvWord.alpha = contentAlpha
        holder.tvReading.alpha = contentAlpha
        holder.tvPriorityBadge.alpha = contentAlpha

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = entry.isEnabled
        holder.switchEnabled.setOnCheckedChangeListener { _, _ ->
            onToggle(entry)
        }

        holder.btnDelete.setOnClickListener {
            onDelete(entry)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<DictionaryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }
}
