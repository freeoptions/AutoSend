package com.autosend.utils

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.Locale

object PinyinUtils {
    private val whitespaceRegex = Regex("\\s+")
    private val nonPinyinRegex = Regex("[^a-z0-9]+")
    private val toneMarkRegex = Regex("\\p{Mn}+")
    private val transliterator: Transliterator? by lazy {
        runCatching {
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        }.getOrNull()
    }

    fun buildSearchIndex(vararg values: String?): String {
        return values
            .asSequence()
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { value ->
                val normalized = normalize(value)
                val pinyin = normalize(runCatching {
                    transliterator?.transliterate(value) ?: value
                }.getOrDefault(value))
                val compactPinyin = pinyin.filter { it.isLetterOrDigit() }
                val initials = pinyin
                    .split(nonPinyinRegex)
                    .filter(String::isNotEmpty)
                    .joinToString("") { it.first().toString() }
                sequenceOf(normalized, pinyin, compactPinyin, initials)
                    .map { it.replace(whitespaceRegex, "") }
            }
            .joinToString("")
    }

    fun matches(searchIndex: String, query: String): Boolean {
        val normalizedQuery = normalize(query).replace(whitespaceRegex, "")
        if (normalizedQuery.isEmpty()) return true
        return searchIndex.contains(normalizedQuery)
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(toneMarkRegex, "")
            .lowercase(Locale.ROOT)
    }
}
