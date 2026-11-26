package dev.vgo.ime.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

class WordPredictorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ids: 0 <s>, 1 xin, 2 chào, 3 lỗi, 4 bạn, 5 người, 6 ngươi, 7 nguồn, 8 Việt, 9 ngô, 10 tôi
    private val modelText = """
        #vgo-predict 1 lang=vi
        U	<s>	0
        U	xin	500
        U	chào	400
        U	lỗi	300
        U	bạn	900
        U	người	2000
        U	ngươi	50
        U	nguồn	300
        U	Việt	800
        U	ngô	40
        U	tôi	3000
        B	0	10:50 1:20
        B	1	3:30 2:20
        B	2	4:40
        T	0	1	2:9 3:5
    """.trimIndent()

    private fun model() = NgramModel.parse(BufferedReader(StringReader(modelText)))

    @Test
    fun contextParsing() {
        assertEquals(PredictContext("", "chào", "Xin"), PredictContext.parse("Xin chào "))
        assertEquals(PredictContext("ng", "chào", "xin"), PredictContext.parse("xin chào ng"))
        assertEquals(PredictContext("", "<s>", null), PredictContext.parse(""))
        assertEquals(PredictContext("", "<s>", null), PredictContext.parse("Ok. "))
        assertEquals(PredictContext("x", "<s>", null), PredictContext.parse("x"))
        assertNull(PredictContext.parse("chào, ").prev1)
        assertEquals(PredictContext("", "<s>", null), PredictContext.parse("dòng 1\n"))
        assertEquals("don't".length, PredictContext.trailingWordLength("I don't"))
        assertEquals(0, PredictContext.trailingWordLength("I don' "))
    }

    @Test
    fun accentInsensitiveCompletionKeepsTypedMarks() {
        assertTrue(VietText.compatible("ngu", "người"))
        assertTrue(VietText.compatible("ngươ", "người"))
        assertTrue(VietText.compatible("ngo", "ngô"))
        assertFalse(VietText.compatible("ngô", "người"))
        assertFalse(VietText.compatible("ngươ", "nguồn"))
        assertTrue(VietText.compatible("ngừ", "người"))
        assertFalse(VietText.compatible("ngứ", "người"))
        assertTrue(VietText.compatible("d", "đường"))
        assertFalse(VietText.compatible("đ", "dưới"))
    }

    @Test
    fun nextWordUsesTrigramThenBigram() {
        val p = WordPredictor(model(), null)
        // "<s> xin" -> trigram chào (9) beats lỗi (5) even though bigram prefers lỗi.
        assertEquals(listOf("chào", "lỗi"), p.suggest(PredictContext.parse("Xin ")).take(2))
        assertEquals(listOf("bạn"), p.suggest(PredictContext.parse("ok, xin chào ")))
        assertEquals(listOf("Tôi", "Xin"), p.suggest(PredictContext.parse("")))
    }

    @Test
    fun completionRanksByFrequencyAndContext() {
        val p = WordPredictor(model(), null)
        assertEquals("người", p.suggest(PredictContext.parse("cảm ơn ngu"))[0])
        assertEquals(listOf("người", "ngươi"), p.suggest(PredictContext.parse("cảm ơn ngươ")))
        assertEquals("Người", p.suggest(PredictContext.parse("Ngu"))[0])
        assertEquals("Việt", p.suggest(PredictContext.parse("tiếng vi"))[0])
    }

    @Test
    fun userHistoryWinsAndPersists() {
        val f = File(tmp.root, "predict-vi.txt")
        val h = UserHistory(f)
        repeat(3) { h.learn("chào", "anh") }
        val p = WordPredictor(model(), h)
        assertEquals("anh", p.suggest(PredictContext.parse("xin chào "))[0])
        h.learn(null, "Thăng")
        h.learn(null, "Thăng")
        assertEquals("Thăng", WordPredictor(model(), h).suggest(PredictContext.parse("anh th"))[0])
        h.save()
        val reloaded = UserHistory(f)
        assertEquals(3, reloaded.next("chào")["anh"])
        assertEquals("Thăng", WordPredictor(model(), reloaded).suggest(PredictContext.parse("anh th"))[0])
    }
}
