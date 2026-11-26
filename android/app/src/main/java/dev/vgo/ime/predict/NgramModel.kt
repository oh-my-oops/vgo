package dev.vgo.ime.predict

import java.io.BufferedReader

/**
 * Read-only n-gram tables built by tools/build_predict.py (format described there).
 * Word id 0 is "<s>" (sentence start). Next-word lists are interleaved [id, count, id, count, ...].
 */
class NgramModel private constructor(
    private val words: Array<String>,
    private val counts: IntArray,
    private val index: HashMap<String, Int>,
    private val bigrams: HashMap<Int, IntArray>,
    private val trigrams: HashMap<Long, IntArray>,
    private val prefixKeys: Array<String>,
    private val prefixIds: IntArray,
) {
    val size: Int get() = words.size
    val totalCount: Long = counts.fold(0L) { acc, c -> acc + c }.coerceAtLeast(1L)

    fun word(id: Int): String = words[id]
    fun count(id: Int): Int = counts[id]
    fun id(word: String): Int = index[word.lowercase()] ?: -1

    fun next(prev: Int): IntArray? = bigrams[prev]
    fun next(prev2: Int, prev1: Int): IntArray? = trigrams[key(prev2, prev1)]

    /** Ids whose diacritic-free form starts with [strippedPrefix], most frequent first, at most [limit]. */
    fun prefixMatches(strippedPrefix: String, limit: Int): List<Int> {
        if (strippedPrefix.isEmpty()) return emptyList()
        var lo = 0
        var hi = prefixKeys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (prefixKeys[mid] < strippedPrefix) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<Int>()
        var i = lo
        while (i < prefixKeys.size && prefixKeys[i].startsWith(strippedPrefix)) {
            out.add(prefixIds[i])
            i++
        }
        out.sortByDescending { counts[it] }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    companion object {
        const val BOS = 0

        private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

        private fun parseEdges(s: String): IntArray {
            val parts = s.split(' ')
            val out = IntArray(parts.size * 2)
            var n = 0
            for (p in parts) {
                val c = p.indexOf(':')
                if (c <= 0) continue
                out[n++] = p.substring(0, c).toInt()
                out[n++] = p.substring(c + 1).toInt()
            }
            return if (n == out.size) out else out.copyOf(n)
        }

        fun parse(reader: BufferedReader): NgramModel {
            val words = ArrayList<String>(65536)
            val counts = ArrayList<Int>(65536)
            val bigrams = HashMap<Int, IntArray>(65536)
            val trigrams = HashMap<Long, IntArray>(262144)
            reader.forEachLine { line ->
                if (line.isEmpty() || line[0] == '#') return@forEachLine
                val f = line.split('\t')
                when (f[0]) {
                    "U" -> if (f.size >= 3) {
                        words.add(f[1]); counts.add(f[2].toIntOrNull() ?: 0)
                    }
                    "B" -> if (f.size >= 3) bigrams[f[1].toInt()] = parseEdges(f[2])
                    "T" -> if (f.size >= 4) trigrams[key(f[1].toInt(), f[2].toInt())] = parseEdges(f[3])
                }
            }
            if (words.isEmpty()) words.add("<s>").also { counts.add(0) }
            val index = HashMap<String, Int>(words.size * 2)
            words.forEachIndexed { i, w -> if (i != BOS) index.putIfAbsent(w.lowercase(), i) }
            val order = (1 until words.size).map { VietText.strip(words[it]) to it }.sortedBy { it.first }
            return NgramModel(
                words = words.toTypedArray(),
                counts = counts.toIntArray(),
                index = index,
                bigrams = bigrams,
                trigrams = trigrams,
                prefixKeys = Array(order.size) { order[it].first },
                prefixIds = IntArray(order.size) { order[it].second },
            )
        }
    }
}
