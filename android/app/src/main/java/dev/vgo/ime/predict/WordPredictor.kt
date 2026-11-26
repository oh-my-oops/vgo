package dev.vgo.ime.predict

/** What the editor shows before the cursor, reduced to what prediction needs. */
data class PredictContext(
    /** Letters of the word being typed right before the cursor ("" after a space). */
    val partial: String,
    /** Previous word, "<s>" at the start of a sentence/field, null after a comma etc. */
    val prev1: String?,
    val prev2: String?,
) {
    val sentenceStart: Boolean get() = prev1 == SENTENCE_START

    companion object {
        const val SENTENCE_START = "<s>"
        private val TOKEN = Regex("""\p{L}+(?:['’]\p{L}+)*|[.!?…]+|\n|\S""")

        fun isWordChar(c: Char): Boolean = c.isLetter()

        /** Length of the word right before the cursor (letters, plus inner apostrophes). */
        fun trailingWordLength(before: String): Int {
            var i = before.length
            while (i > 0) {
                val c = before[i - 1]
                if (isWordChar(c)) {
                    i--
                } else if ((c == '\'' || c == '’') && i >= 2 && isWordChar(before[i - 2]) && i < before.length) {
                    i--
                } else {
                    break
                }
            }
            return before.length - i
        }

        fun parse(before: String): PredictContext {
            val partialLen = trailingWordLength(before)
            val partial = before.substring(before.length - partialLen)
            val rest = before.substring(0, before.length - partialLen)
            if (partialLen > 0 && rest.isNotEmpty() && !rest.last().isWhitespace() && rest.last() !in "\"'“‘([{") {
                // Inside something like "abc-de" or "x/y": no reliable context.
                return PredictContext(partial, null, null)
            }
            val tokens = TOKEN.findAll(rest.takeLast(160)).map { it.value }.toList()
            // Context is unknown if the visible text was cut mid-sentence and is all one chunk.
            if (tokens.isEmpty()) {
                val start = rest.length <= 160
                return PredictContext(partial, if (start) SENTENCE_START else null, null)
            }
            fun classify(t: String): String? = when {
                t[0].isLetter() -> t
                t == "\n" || t[0] in ".!?…" -> SENTENCE_START
                else -> null
            }
            val p1 = classify(tokens.last())
            if (p1 == null || p1 == SENTENCE_START) return PredictContext(partial, p1, null)
            val p2 = if (tokens.size >= 2) classify(tokens[tokens.size - 2]) else if (rest.length <= 160) SENTENCE_START else null
            return PredictContext(partial, p1, p2)
        }
    }
}

/**
 * Gboard-style suggestion strip logic: completes the word being typed and predicts the next word,
 * mixing trigram/bigram/unigram corpus statistics (interpolated) with the user's own history.
 */
class WordPredictor(
    private val model: NgramModel,
    private val history: UserHistory?,
) {
    fun suggest(ctx: PredictContext, max: Int = 3): List<String> {
        val p1 = ctx.prev1?.let { if (it == PredictContext.SENTENCE_START) NgramModel.BOS else model.id(it) } ?: -1
        val p2 = ctx.prev2?.let { if (it == PredictContext.SENTENCE_START) NgramModel.BOS else model.id(it) } ?: -1
        val tri = if (p1 > 0 && p2 >= 0) model.next(p2, p1) else null
        val bi = if (p1 >= 0) model.next(p1) else null
        val triTotal = tri?.let { sumCounts(it) } ?: 0
        val biTotal = bi?.let { sumCounts(it) } ?: 0
        val userNext = if (ctx.prev1 != null && ctx.prev1 != PredictContext.SENTENCE_START) history?.next(ctx.prev1) else null
        val userNextTotal = userNext?.values?.sum() ?: 0

        val partial = ctx.partial
        val stripped = VietText.strip(partial)
        val scores = HashMap<String, Double>()

        fun accept(word: String): Boolean = partial.isEmpty() || VietText.compatible(partial, word)

        fun add(word: String, s: Double) {
            if (!accept(word)) return
            val k = word.lowercase()
            scores[k] = (scores[k] ?: 0.0) + s
        }

        if (tri != null) forEdges(tri) { id, c -> add(model.word(id), W_TRI * c / triTotal) }
        if (bi != null) forEdges(bi) { id, c -> add(model.word(id), W_BI * c / biTotal) }
        if (userNext != null && userNextTotal > 0) {
            for ((w, c) in userNext) add(w, W_USER_NEXT * c / userNextTotal + W_USER_NEXT * 0.1 * c.coerceAtMost(5))
        }
        if (partial.isNotEmpty()) {
            for (id in model.prefixMatches(stripped, 60)) {
                add(model.word(id), W_UNI * model.count(id) / model.totalCount * UNI_SCALE)
            }
            history?.prefixMatches(stripped)?.forEach { w -> add(w, W_USER_UNI * history.unigram(w).coerceAtMost(10)) }
            // Words spelled exactly as typed so far (marks included) beat accent-insensitive matches.
            val lowerPartial = partial.lowercase()
            for (k in scores.keys.toList()) if (k.startsWith(lowerPartial)) scores[k] = scores[k]!! * EXACT_BONUS
        } else if (scores.isEmpty()) {
            return emptyList()
        }
        // Boost by the user's overall usage so their vocabulary wins ties.
        if (history != null) for (k in scores.keys.toList()) {
            val u = history.unigram(k)
            if (u > 0) scores[k] = scores[k]!! * (1.0 + 0.15 * u.coerceAtMost(10))
        }

        return scores.entries.sortedByDescending { it.value }.take(max).map { (k, _) ->
            val id = model.id(k)
            val base = if (id > 0) model.word(id) else history?.displayOf(k) ?: k
            applyCase(base, partial, ctx.sentenceStart)
        }
    }

    private inline fun forEdges(edges: IntArray, f: (Int, Int) -> Unit) {
        var i = 0
        while (i + 1 < edges.size) {
            if (edges[i] != NgramModel.BOS) f(edges[i], edges[i + 1])
            i += 2
        }
    }

    private fun sumCounts(edges: IntArray): Int {
        var s = 0
        var i = 1
        while (i < edges.size) { s += edges[i]; i += 2 }
        return s.coerceAtLeast(1)
    }

    companion object {
        private const val W_TRI = 0.6
        private const val W_BI = 0.3
        private const val W_UNI = 0.1
        private const val UNI_SCALE = 50.0
        private const val W_USER_NEXT = 0.8
        private const val W_USER_UNI = 0.002
        private const val EXACT_BONUS = 1.5

        fun applyCase(word: String, typed: String, sentenceStart: Boolean): String = when {
            typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.titlecase() }
            typed.isEmpty() && sentenceStart -> word.replaceFirstChar { it.titlecase() }
            else -> word
        }
    }
}
