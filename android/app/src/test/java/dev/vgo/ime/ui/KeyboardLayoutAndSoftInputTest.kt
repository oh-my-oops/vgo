package dev.vgo.ime.ui

import android.view.inputmethod.EditorInfo
import dev.vgo.ime.engine.EngineSnapshot
import dev.vgo.ime.engine.InputMethodMode
import dev.vgo.ime.engine.NativeVgoEngineBridge
import dev.vgo.ime.ime.EditorPolicy
import dev.vgo.ime.ime.ImeSessionController
import dev.vgo.ime.ime.SessionCallbacks
import dev.vgo.ime.ime.TextBridge
import dev.vgo.ime.prefs.VgoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KeyboardLayoutAndSoftInputTest {

    @Test
    fun gboardTabletLayoutMatchesExactRowStructureAndCleanKeys() {
        val defaults = VgoSettings()
        assertEquals(InputMethodMode.TELEX, defaults.softMethod)
        assertEquals(InputMethodMode.VNI, defaults.hardwareMethod)
        assertTrue(defaults.noUnderline)
        assertTrue(defaults.clipboardEnabled)
        assertFalse(defaults.showVniHints)

        val rows = KeyboardLayoutBuilder.buildRows(
            page = KeyboardPage.ALPHA,
            shiftState = ShiftState.OFF,
            mode = InputMethodMode.VNI,
            vnEnabled = true,
            showNumberRow = true,
            showVniHints = false,
            split = false,
            isLandscape = false,
            imeAction = EditorInfo.IME_ACTION_SEARCH,
            isMultiline = false,
        )
        assertEquals(5, rows.size)

        // Row 0: ` 1..0 ⌫
        assertEquals(
            listOf("`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫"),
            rows[0].map { it.label },
        )
        assertEquals(KeyIcon.BACKSPACE, rows[0].last().icon)

        // Row 1: Tab q..p \
        assertEquals(
            listOf("⇥", "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "\\"),
            rows[1].map { it.label },
        )
        assertEquals(KeyIcon.TAB, rows[1][0].icon)

        // Row 2: CapsLock a..l Enter(Search icon), no apostrophe key
        assertEquals(
            listOf("⇪", "a", "s", "d", "f", "g", "h", "j", "k", "l", "↵"),
            rows[2].map { it.label },
        )
        assertEquals(KeyAction.CapsLock, rows[2][0].action)
        assertEquals(KeyIcon.SEARCH, rows[2].last().icon)

        // Row 3: Shift z..m , . Shift (no ? key)
        assertEquals(
            listOf("⇧", "z", "x", "c", "v", "b", "n", "m", ",", ".", "⇧"),
            rows[3].map { it.label },
        )

        // Row 4: ?123 ☺ 🌐 Tiếng Việt ◀ ▶ ?123
        assertEquals(
            listOf("?123", "☺", "🌐", "Tiếng Việt", "◀", "▶", "?123"),
            rows[4].map { it.label },
        )
        assertEquals(KeyIcon.EMOJI, rows[4][1].icon)
        assertEquals(KeyIcon.GLOBE, rows[4][2].icon)
        assertEquals(KeyAction.ToggleVn, rows[4][2].action)
        assertEquals(KeyIcon.ARROW_LEFT, rows[4][4].icon)
        assertEquals(KeyIcon.ARROW_RIGHT, rows[4][5].icon)

        // Clean Gboard look: zero corner hints printed, long-press options still present on letters
        assertTrue(rows.flatten().all { it.hint.isEmpty() })
        assertTrue(rows[1][1].longPressOptions.isNotEmpty())
    }

    @Test
    fun englishSpaceLabelAndSplitLayoutWorkCleanly() {
        val rows = KeyboardLayoutBuilder.buildRows(
            page = KeyboardPage.ALPHA,
            shiftState = ShiftState.OFF,
            mode = InputMethodMode.VNI,
            vnEnabled = false,
            showNumberRow = true,
            showVniHints = false,
            split = true,
            isLandscape = true,
            imeAction = EditorInfo.IME_ACTION_SEND,
            isMultiline = false,
        )
        assertEquals(5, rows.size)
        for ((idx, row) in rows.withIndex()) {
            assertTrue("Row $idx missing center split spacer", row.any { it.kind == KeyKind.SPACER })
        }
        assertTrue(rows[2].any { it.action == KeyAction.Enter && it.icon == KeyIcon.SEND })
        assertTrue(rows[4].any { it.action == KeyAction.Space && it.label == "English" })
    }

    @Test
    fun numpadMarksDigitsAsNumpad() {
        val rows = KeyboardLayoutBuilder.buildRows(
            page = KeyboardPage.NUMPAD,
            shiftState = ShiftState.OFF,
            mode = InputMethodMode.VNI,
            vnEnabled = true,
            showNumberRow = true,
            showVniHints = false,
            split = false,
            isLandscape = false,
            imeAction = EditorInfo.IME_ACTION_DONE,
            isMultiline = false,
        )
        val digitActions = rows.flatten().mapNotNull { it.action as? KeyAction.Text }
        assertTrue(digitActions.isNotEmpty())
        assertTrue(digitActions.all { it.isNumpad })
        assertTrue(rows[3].any { it.action == KeyAction.Enter && it.icon == KeyIcon.DONE })
    }

    @Test
    fun softInputVniTypingFlipChipAndDeleteWord() {
        val libPath = System.getProperty("vgo.jni.lib")
        assumeTrue("Host JNI lib not configured", !libPath.isNullOrBlank())
        val baseDir = File(System.getProperty("vgo.data.dir") ?: "../../data").canonicalFile
        val userDir = Files.createTempDirectory("vgo-soft-test-").toFile()

        val bridge = NativeVgoEngineBridge().apply {
            init(baseDir.absolutePath, userDir.absolutePath)
            reset()
        }
        var lastSnap = EngineSnapshot()
        val ctrl = ImeSessionController(
            engine = bridge,
            callbacks = object : SessionCallbacks {
                override fun onSnapshotChanged(snapshot: EngineSnapshot) {
                    lastSnap = snapshot
                }
                override fun onVnEnabledToggled(enabled: Boolean, method: InputMethodMode) = Unit
            },
        )
        ctrl.applySettings(VgoSettings(softMethod = InputMethodMode.VNI, noUnderline = true), null)
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.soft.test", initialSelEnd = 0))

        val buf = StringBuilder()
        val tb = object : TextBridge {
            override fun beginBatchEdit() = Unit
            override fun endBatchEdit() = Unit
            override fun setComposingText(text: CharSequence, newCursorPosition: Int) = error("Must not call setComposingText in noUnderline mode")
            override fun finishComposingText() = Unit
            override fun commitText(text: CharSequence, newCursorPosition: Int) { buf.append(text) }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
                val d = beforeLength.coerceAtMost(buf.length)
                if (d > 0) buf.delete(buf.length - d, buf.length)
            }
            override fun getTextBeforeCursor(n: Int): String = buf.takeLast(n).toString()
            override fun sendDownUpKeyEvent(keyCode: Int) = Unit
            override fun performEditorAction(imeAction: Int): Boolean = false
        }

        for (ch in "tie6ng1 Vie65t") {
            ctrl.onSoftText(ch.toString(), tb)
        }
        assertEquals("tiếng Việt", buf.toString())
        assertTrue(lastSnap.hasFlipCandidate)
        assertEquals("Vie65t", lastSnap.altDisplay)

        ctrl.onSoftFlip(tb)
        assertEquals("tiếng Vie65t", buf.toString())

        ctrl.onSoftFlip(tb)
        assertEquals("tiếng Việt", buf.toString())

        ctrl.finalizeComposing(tb, ' '.code)
        ctrl.onSoftDeleteWord(tb)
        assertEquals("tiếng ", buf.toString())
    }
}
