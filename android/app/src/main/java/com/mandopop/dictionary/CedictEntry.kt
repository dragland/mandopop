package com.mandopop.dictionary

data class CedictEntry(
    val simplified: String,
    val pinyin: String,
    val definitions: List<String>,
)
