package com.menwitz.humanliketyping.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.menwitz.humanliketyping.R
import com.menwitz.humanliketyping.data.model.SentenceEntry
import com.menwitz.humanliketyping.data.repository.SentenceRepository
import com.menwitz.humanliketyping.ui.adapter.SentenceAdapter

class SentencesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var adapter: SentenceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sentences)
        supportActionBar?.title = "Manage Sentences"

        recyclerView = findViewById(R.id.recyclerSentences)
        fabAdd = findViewById(R.id.fabAdd)

        val list = SentenceRepository.loadDefault(this).toMutableList()
        adapter = SentenceAdapter(list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener {
            adapter.addEntry(SentenceEntry(System.currentTimeMillis(), ""))
            recyclerView.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onPause() {
        super.onPause()
        SentenceRepository.saveAll(this, adapter.getAll())
        Toast.makeText(this, getString(R.string.sentences_saved), Toast.LENGTH_SHORT).show()
    }
}
