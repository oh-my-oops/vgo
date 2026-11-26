package dev.vgo.ime.predict

import java.text.Normalizer

/** Diacritic helpers so "ngu" / "ngư" can complete to "người" while "nga" can't become "ngô". */
object VietText {
    private const val TONE_MARKS = "̣̀́̉̃"

    /** Lowercase, all diacritics removed, đ -> d ("Người" -> "nguoi"). */
    fun strip(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (c in d) {
            if (c in '̀'..'ͯ') continue
            sb.append(if (c == 'đ') 'd' else c)
        }
        return sb.toString()
    }

    /** Lowercase with the tone mark removed but vowel hats kept ("người" -> "ngươi"). */
    fun noTone(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (c in d) if (c !in TONE_MARKS) sb.append(c)
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    /** The tone mark of the word (one of the five combining marks), or null for "thanh ngang". */
    fun tone(s: String): Char? {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        return d.firstOrNull { it in TONE_MARKS }
    }

    /**
     * True when [candidate] may complete what the user typed in [typed]:
     * same letters ignoring marks, and every mark the user already typed (hat/horn/đ/tone) is kept.
     */
    fun compatible(typed: String, candidate: String): Boolean {
        val ts = strip(typed)
        val cs = strip(candidate)
        if (!cs.startsWith(ts)) return false
        val tn = noTone(typed)
        val cn = noTone(candidate)
        if (tn.length != ts.length || cn.length != cs.length) return cn.startsWith(tn)
        for (i in tn.indices) {
            if (tn[i] != cn[i] && tn[i] != ts[i]) return false
        }
        val tt = tone(typed) ?: return true
        return tone(candidate) == tt
    }
}
