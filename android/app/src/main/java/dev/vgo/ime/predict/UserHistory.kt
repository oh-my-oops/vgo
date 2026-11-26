package dev.vgo.ime.predict

import java.io.File

/**
 * Words and word pairs the user actually typed, so their own phrases rank first (like Gboard's
 * personal dictionary). Stored only in app-private storage; never fed from password/incognito fields.
 */
class UserHistory(private val file: File?) {
    private val uni = HashMap<String, Int>()
    private val display = HashMap<String, String>()
    private val bi = HashMap<String, HashMap<String, Int>>()
    private var biSize = 0
    private var dirty = false

    init {
        load()
    }

    fun unigram(word: String): Int = uni[word.lowercase()] ?: 0
    fun displayOf(lower: String): String = display[lower] ?: lower
    fun next(prev: String): Map<String, Int> = bi[prev.lowercase()] ?: emptyMap()

    /** Known user words whose diacritic-free form starts with [strippedPrefix] (used >= 2 times). */
    fun prefixMatches(strippedPrefix: String): List<String> =
        uni.entries.filter { it.value >= 2 && VietText.strip(it.key).startsWith(strippedPrefix) }
            .sortedByDescending { it.value }.take(20).map { it.key }

    fun learn(prev: String?, word: String) {
        if (word.isEmpty() || word.length > MAX_WORD_LEN || !word.all { it.isLetter() || it == '\'' }) return
        val w = word.lowercase()
        uni[w] = (uni[w] ?: 0) + 1
        if (word != w || display[w] == null) display[w] = word
        if (prev != null) {
            val p = prev.lowercase()
            val m = bi.getOrPut(p) { HashMap() }
            if (m[w] == null) biSize++
            m[w] = (m[w] ?: 0) + 1
        }
        dirty = true
        prune()
    }

    fun save() {
        val f = file ?: return
        if (!dirty) return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { out ->
                for ((w, c) in uni) out.append("U\t").append(display[w] ?: w).append('\t').append(c.toString()).append('\n')
                for ((p, m) in bi) for ((w, c) in m) {
                    out.append("B\t").append(p).append('\t').append(w).append('\t').append(c.toString()).append('\n')
                }
            }
            tmp.renameTo(f)
            dirty = false
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            f.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 3 && p[0] == "U" -> {
                        val w = p[1].lowercase()
                        uni[w] = p[2].toIntOrNull() ?: 1
                        display[w] = p[1]
                    }
                    p.size == 4 && p[0] == "B" -> {
                        bi.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: 1
                        biSize++
                    }
                }
            }
        }
    }

    /** Keeps the store bounded: drop the least used half when it grows past the cap. */
    private fun prune() {
        if (uni.size > MAX_WORDS) {
            val keep = uni.entries.sortedByDescending { it.value }.take(MAX_WORDS / 2).mapTo(HashSet()) { it.key }
            uni.keys.retainAll(keep)
            display.keys.retainAll(keep)
        }
        if (biSize > MAX_PAIRS) {
            val all = bi.flatMap { (p, m) -> m.map { Triple(p, it.key, it.value) } }
                .sortedByDescending { it.third }.take(MAX_PAIRS / 2)
            bi.clear()
            for ((p, w, c) in all) bi.getOrPut(p) { HashMap() }[w] = c
            biSize = all.size
        }
    }

    companion object {
        const val MAX_WORD_LEN = 30
        const val MAX_WORDS = 20000
        const val MAX_PAIRS = 40000
    }
}
