package com.menwitz.humanliketyping.data.repository

import android.content.Context
import androidx.preference.PreferenceManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okio.buffer
import okio.source
import com.menwitz.humanliketyping.data.model.SentenceEntry

object SentenceRepository {

    private const val PREF_KEY_SENTENCES = "pref_key_sentences"

    /** Load sentences: first from SharedPreferences, falling back to default JSON in assets */
    fun loadDefault(context: Context): List<SentenceEntry> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val jsonString = prefs.getString(PREF_KEY_SENTENCES, null)
        return if (!jsonString.isNullOrEmpty()) {
            parseJson(jsonString)
        } else {
            val assetJson = context.assets.open("default_sentences.json")
                .source().buffer().readUtf8()
            parseJson(assetJson)
        }
    }

    /** Save the provided list of entries back to SharedPreferences */
    fun saveAll(context: Context, entries: List<SentenceEntry>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = toJson(entries)
        prefs.edit().putString(PREF_KEY_SENTENCES, json).apply()
    }

    private fun parseJson(json: String): List<SentenceEntry> {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val type = Types.newParameterizedType(List::class.java, SentenceEntry::class.java)
        val adapter = moshi.adapter<List<SentenceEntry>>(type)
        return adapter.fromJson(json) ?: emptyList()
    }

    private fun toJson(entries: List<SentenceEntry>): String {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val type = Types.newParameterizedType(List::class.java, SentenceEntry::class.java)
        val adapter = moshi.adapter<List<SentenceEntry>>(type)
        return adapter.toJson(entries)
    }
}
