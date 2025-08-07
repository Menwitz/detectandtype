package com.menwitz.humanliketyping.data.model

data class SentenceEntry(
    val id: Long,
    var text: String,
    var scenarioTag: String? = null
)
