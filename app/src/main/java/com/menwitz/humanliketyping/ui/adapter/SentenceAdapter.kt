package com.menwitz.humanliketyping.ui.adapter

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.data.model.SentenceEntry

class SentenceAdapter(
    private val items: MutableList<SentenceEntry>
) : RecyclerView.Adapter<SentenceAdapter.ViewHolder>() {

    inner class ViewHolder(parent: ViewGroup) :
        RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sentence, parent, false)
        ) {
        private val editSentence: EditText = itemView.findViewById(R.id.editSentence)
        private val buttonDelete: ImageButton = itemView.findViewById(R.id.buttonDelete)
        private var currentEntry: SentenceEntry? = null

        private val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentEntry?.text = s.toString()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        fun bind(entry: SentenceEntry) {
            currentEntry = entry
            editSentence.removeTextChangedListener(textWatcher)
            editSentence.setText(entry.text)
            editSentence.addTextChangedListener(textWatcher)

            buttonDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(parent)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /** Add a new entry to the list and notify adapter */
    fun addEntry(entry: SentenceEntry) {
        items.add(entry)
        notifyItemInserted(items.size - 1)
    }

    /** Expose current list to caller (e.g. for saving) */
    fun getAll(): List<SentenceEntry> = items.toList()
}
