package com.autosend.utils

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.Locale

object PinyinUtils {
    private val whitespaceRegex = Regex("\\s+")
    private val toneMarkRegex = Regex("\\p{Mn}+")
    private val nonPinyinRegex = Regex("[^a-z0-9]+")
    // ICU 通常只给出一个读音；为常见多音字补充候选，不枚举整句的排列组合。
    private val polyphonicReadings = mapOf(
        '行' to listOf("xing", "hang", "heng"), '重' to listOf("zhong", "chong"),
        '长' to listOf("chang", "zhang"), '乐' to listOf("le", "yue"),
        '朝' to listOf("chao", "zhao"), '单' to listOf("dan", "shan", "chan"),
        '解' to listOf("jie", "xie"), '还' to listOf("hai", "huan"),
        '曾' to listOf("ceng", "zeng"), '区' to listOf("qu", "ou"),
        '仇' to listOf("chou", "qiu"), '传' to listOf("chuan", "zhuan"),
        '藏' to listOf("cang", "zang"), '都' to listOf("dou", "du"),
        '得' to listOf("de", "dei"), '地' to listOf("di", "de"),
        '的' to listOf("de", "di"), '着' to listOf("zhe", "zhao", "zhuo"),
        '薄' to listOf("bao", "bo"), '差' to listOf("cha", "chai", "ci"),
        '调' to listOf("diao", "tiao"),
        '觉' to listOf("jue", "jiao"), '角' to listOf("jiao", "jue"),
        '系' to listOf("xi", "ji"), '数' to listOf("shu", "shuo"),
        '省' to listOf("sheng", "xing"), '盛' to listOf("sheng", "cheng"),
        '宿' to listOf("su", "xiu"), '种' to listOf("zhong", "chong"),
        '会' to listOf("hui", "kuai"), '强' to listOf("qiang", "jiang"),
        '弹' to listOf("dan", "tan"), '血' to listOf("xue", "xie"),
        '给' to listOf("gei", "ji"), '便' to listOf("bian", "pian"),
        '参' to listOf("can", "cen", "shen"), '员' to listOf("yuan", "yun"),
        '没' to listOf("mei", "mo"), '咽' to listOf("yan", "ye"),
        '咳' to listOf("ke", "hai"), '柏' to listOf("bai", "bo"),
        '厦' to listOf("xia", "sha"), '折' to listOf("zhe", "she"),
        '塞' to listOf("sai", "se"), '钥' to listOf("yao", "yue"),
        '露' to listOf("lu", "lou"), '率' to listOf("lv", "lu", "shuai"),
        '绿' to listOf("lv", "lu"), '六' to listOf("liu", "lu")
    )
    private val readingCache = object : LinkedHashMap<Int, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>?): Boolean = size > 2048
    }
    private val transliterator: Transliterator? by lazy {
        runCatching {
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        }.getOrNull()
    }

    data class SearchIndex internal constructor(internal val entries: List<SearchEntry>)

    internal data class SearchEntry(val text: String, val pinyin: String, val initials: String,
                                   val readings: List<List<String>>)

    fun buildSearchIndex(vararg values: String?): SearchIndex {
        return SearchIndex(values
            .asSequence()
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { value ->
                val converted = normalize(transliterate(value))
                SearchEntry(
                    text = normalize(value).replace(whitespaceRegex, ""),
                    pinyin = converted.replace(nonPinyinRegex, ""),
                    initials = converted.split(nonPinyinRegex).filter(String::isNotEmpty)
                        .joinToString("") { it.first().toString() },
                    readings = buildReadings(value)
                )
            }
            .toList())
    }

    fun matches(searchIndex: SearchIndex?, query: String): Boolean {
        val normalizedQuery = normalize(query).replace(whitespaceRegex, "")
        if (normalizedQuery.isEmpty()) return true
        return searchIndex?.entries?.any { entry ->
            entry.text.contains(normalizedQuery) || entry.pinyin.contains(normalizedQuery) || entry.initials.contains(normalizedQuery) ||
                matchesReadings(entry.readings, normalizedQuery, initialsOnly = false) ||
                matchesReadings(entry.readings, normalizedQuery, initialsOnly = true)
        } == true
    }

    private fun buildReadings(value: String): List<List<String>> {
        val tokens = mutableListOf<List<String>>()
        val latinWord = StringBuilder()
        fun flushLatinWord() {
            if (latinWord.isNotEmpty()) {
                tokens.add(listOf(normalize(latinWord.toString())))
                latinWord.clear()
            }
        }
        value.codePoints().toArray().forEach { codePoint ->
            if (codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code || codePoint in '0'.code..'9'.code) {
                latinWord.append(codePoint.toChar())
            } else {
                flushLatinWord()
                readingsFor(codePoint).takeIf { it.isNotEmpty() }?.let { tokens.add(it) }
            }
        }
        flushLatinWord()
        return tokens
    }

    private fun readingsFor(codePoint: Int): List<String> = synchronized(readingCache) {
        readingCache.getOrPut(codePoint) {
            val text = String(Character.toChars(codePoint))
            val primary = normalize(transliterate(text)).replace(nonPinyinRegex, "")
            val alternatives = if (codePoint <= Char.MAX_VALUE.code) polyphonicReadings[codePoint.toChar()] else null
            (listOf(primary) + alternatives.orEmpty()).filter(String::isNotEmpty).distinct()
        }
    }

    private fun transliterate(value: String): String = runCatching {
        // Transliterator 有内部状态，缓存和其他调用不能同时使用同一实例。
        val converter = transliterator ?: return@runCatching value
        synchronized(converter) { converter.transliterate(value) }
    }.getOrDefault(value)

    private fun matchesReadings(readings: List<List<String>>, query: String, initialsOnly: Boolean): Boolean {
        var offsets = emptySet<Int>()
        for (alternatives in readings) {
            val next = HashSet<Int>()
            for (reading in alternatives) {
                val token = if (initialsOnly) reading.take(1) else reading
                // 延续前一个字；同时允许从任意字/音节中间开始，与原有包含搜索一致。
                for (offset in offsets) {
                    val remaining = query.length - offset
                    if (token.length >= remaining && token.regionMatches(0, query, offset, remaining)) return true
                    if (query.startsWith(token, offset)) next.add(offset + token.length)
                }
                for (start in token.indices) {
                    val suffix = token.substring(start)
                    if (suffix.startsWith(query)) return true
                    if (query.startsWith(suffix)) next.add(suffix.length)
                }
            }
            offsets = next
        }
        return false
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(toneMarkRegex, "")
            .lowercase(Locale.ROOT)
    }
}
