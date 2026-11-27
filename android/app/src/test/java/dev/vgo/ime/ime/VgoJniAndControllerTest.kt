package dev.vgo.ime.ime

import dev.vgo.ime.engine.EngineSnapshot
import dev.vgo.ime.engine.InputMethodMode
import dev.vgo.ime.engine.NativeVgoEngineBridge
import dev.vgo.ime.prefs.InputSource
import dev.vgo.ime.prefs.VgoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * End-to-end JNI + ImeSessionController tests running the real vgo C++ engine.
 * Also mirrors RustDesk Android's _handleNonIOSSoftKeyboardInput prefix-diff algorithm
 * to verify zero dropped or garbled characters over remote desktop.
 */
class VgoJniAndControllerTest {

    private lateinit var bridge: NativeVgoEngineBridge
    private lateinit var userDir: File
    private lateinit var baseDir: File
    private var lastSnapshot = EngineSnapshot()
    private val toggles = mutableListOf<Pair<Boolean, InputMethodMode>>()

    @Before
    fun setUp() {
        val libPath = System.getProperty("vgo.jni.lib")
        assumeTrue("Host JNI lib not configured (set -Dvgo.jni.lib=...)", !libPath.isNullOrBlank())

        baseDir = File(System.getProperty("vgo.data.dir") ?: "../../data").canonicalFile
        assertTrue("Missing vgo data dir: $baseDir", File(baseDir, "en-base.txt").exists())

        userDir = Files.createTempDirectory("vgo-android-test-").toFile()
        bridge = NativeVgoEngineBridge()
        bridge.init(baseDir.absolutePath, userDir.absolutePath)
        bridge.reset()
        toggles.clear()
        lastSnapshot = EngineSnapshot()
    }

    private fun newController(settings: VgoSettings = VgoSettings()): ImeSessionController {
        val ctrl = ImeSessionController(
            engine = bridge,
            callbacks = object : SessionCallbacks {
                override fun onSnapshotChanged(snapshot: EngineSnapshot) {
                    lastSnapshot = snapshot
                }

                override fun onVnEnabledToggled(enabled: Boolean, method: InputMethodMode) {
                    toggles += enabled to method
                }
            },
        )
        ctrl.applySettings(settings, null)
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test"))
        return ctrl
    }

    /**
     * Simulates both an Android EditText (localText) and RustDesk's hidden Flutter TextField
     * prefix-diff pipeline (remoteText), asserting they stay identical after every edit.
     */
    private class FakeAndroidAndRustDeskField : TextBridge {
        private val buf = StringBuilder("1") // RustDesk initText prefix
        private var compStart = -1
        private var compEnd = -1
        private var batchDepth = 0
        private var oldValue = "1"
        private val remote = StringBuilder()

        val localText: String get() = buf.substring(1)
        val remoteText: String get() = remote.toString()

        override fun beginBatchEdit() {
            batchDepth++
        }

        override fun endBatchEdit() {
            batchDepth--
            if (batchDepth == 0) onFieldChanged()
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
            if (compStart >= 0 && compEnd >= compStart) {
                buf.replace(compStart, compEnd, text.toString())
                compEnd = compStart + text.length
            } else {
                compStart = buf.length
                buf.append(text)
                compEnd = buf.length
            }
            if (batchDepth == 0) onFieldChanged()
        }

        override fun finishComposingText() {
            compStart = -1
            compEnd = -1
            if (batchDepth == 0) onFieldChanged()
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int) {
            if (compStart >= 0 && compEnd >= compStart) {
                buf.replace(compStart, compEnd, text.toString())
                compStart = -1
                compEnd = -1
            } else {
                buf.append(text)
            }
            if (batchDepth == 0) onFieldChanged()
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            val del = beforeLength.coerceAtMost(buf.length - 1) // preserve initText '1'
            if (del > 0) {
                buf.delete(buf.length - del, buf.length)
                compStart = -1
                compEnd = -1
            }
            if (batchDepth == 0) onFieldChanged()
        }

        override fun getTextBeforeCursor(n: Int): String {
            val start = (buf.length - n).coerceAtLeast(1)
            return buf.substring(start, buf.length)
        }

        var enterKeys = 0

        override fun sendDownUpKeyEvent(keyCode: Int) {
            if (keyCode == ImeSessionController.KEYCODE_ENTER) {
                enterKeys++
                buf.append('\n')
                onFieldChanged()
            } else if (keyCode == ImeSessionController.KEYCODE_DEL && buf.length > 1) {
                buf.deleteCharAt(buf.length - 1)
                onFieldChanged()
            }
        }

        override fun performEditorAction(imeAction: Int): Boolean = false

        fun injectRawKeyPassthrough(ch: Char) {
            buf.append(ch)
            onFieldChanged()
        }

        fun injectRawBackspacePassthrough() {
            if (buf.length > 1) {
                buf.deleteCharAt(buf.length - 1)
                onFieldChanged()
            }
        }

        // Mirrors rustdesk/flutter/lib/mobile/pages/remote_page.dart:_handleNonIOSSoftKeyboardInput
        private fun onFieldChanged() {
            val newValue = buf.toString()
            val prev = oldValue
            oldValue = newValue
            var prefix = 0
            val maxPrefix = minOf(prev.length, newValue.length)
            while (prefix < maxPrefix && prev[prefix] == newValue[prefix]) {
                prefix++
            }
            if (prefix == prev.length && prefix == newValue.length) return
            if (prefix == newValue.length) {
                if (remote.isNotEmpty()) remote.deleteCharAt(remote.length - 1)
            } else {
                val backs = prev.length - prefix
                repeat(backs) {
                    if (remote.isNotEmpty()) remote.deleteCharAt(remote.length - 1)
                }
                remote.append(newValue.substring(prefix))
            }
        }
    }

    private fun typeHardwareString(ctrl: ImeSessionController, field: FakeAndroidAndRustDeskField, s: String) {
        for (ch in s) {
            val keyCode = when (ch) {
                ' ' -> ImeSessionController.KEYCODE_SPACE
                '\n' -> ImeSessionController.KEYCODE_ENTER
                else -> 29 + (ch.lowercaseChar() - 'a').coerceIn(0, 25)
            }
            val ev = HwKeyEvent(
                keyCode = keyCode,
                unicodeChar = ch.code,
                shiftPressed = ch.isUpperCase(),
            )
            val consumed = ctrl.onHardwareKeyDown(ev, field)
            ctrl.onHardwareKeyUp(ev, field)
            if (!consumed) {
                field.injectRawKeyPassthrough(ch)
            }
        }
        ctrl.finalizeComposing(field, 0)
    }

    @Test
    fun allVniCasesPassThroughControllerAndRustDeskDiff() {
        val casesFile = File(baseDir.parentFile, "test/vni-cases.txt")
        assertTrue("Missing vni-cases.txt: $casesFile", casesFile.exists())

        for (noUnderline in listOf(true, false)) {
            val ctrl = newController(VgoSettings(hardwareMethod = InputMethodMode.VNI, noUnderline = noUnderline))
            var count = 0
            for (rawLine in casesFile.readLines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split("|")
                if (parts.size != 3) continue
                val (id, keys, expected) = parts
                val field = FakeAndroidAndRustDeskField()
                ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.vni.$id"))
                typeHardwareString(ctrl, field, keys)
                assertEquals("VNI (noUnderline=$noUnderline) local mismatch [$id] keys='$keys'", expected, field.localText)
                assertEquals("VNI (noUnderline=$noUnderline) RustDesk mismatch [$id] keys='$keys'", expected, field.remoteText)
                count++
            }
            assertTrue("Expected >= 20 VNI cases, got $count", count >= 20)
        }
    }

    @Test
    fun allTelexSentenceCasesPassThroughControllerAndRustDeskDiff() {
        val casesFile = File(baseDir.parentFile, "test/vikey-cases.txt")
        assertTrue("Missing vikey-cases.txt: $casesFile", casesFile.exists())

        for (noUnderline in listOf(true, false)) {
            val ctrl = newController(VgoSettings(hardwareMethod = InputMethodMode.TELEX, noUnderline = noUnderline))
            var count = 0
            for (rawLine in casesFile.readLines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split("|")
                if (parts.size != 3) continue
                val (id, keys, expected) = parts
                val field = FakeAndroidAndRustDeskField()
                ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.telex.$id"))
                typeHardwareString(ctrl, field, keys)
                assertEquals("Telex (noUnderline=$noUnderline) local mismatch [$id] keys='$keys'", expected, field.localText)
                assertEquals("Telex (noUnderline=$noUnderline) RustDesk mismatch [$id] keys='$keys'", expected, field.remoteText)
                count++
            }
            assertTrue("Expected >= 35 Telex cases, got $count", count >= 35)
        }
    }

    @Test
    fun vniBackspaceAndEscFlipMatchLocalAndRustDesk() {
        val ctrl = newController(VgoSettings(hardwareMethod = InputMethodMode.VNI))
        val field = FakeAndroidAndRustDeskField()
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.esc"))

        // Esc when idle passes through to app.
        assertFalse(ctrl.onHardwareKeyDown(HwKeyEvent(ImeSessionController.KEYCODE_ESCAPE, 0), field))

        // Type "d9i" -> "đi", backspace -> "đ", backspace -> "d", backspace -> ""
        for (ch in "d9i") {
            val ev = HwKeyEvent(keyCode = 30, unicodeChar = ch.code)
            assertTrue(ctrl.onHardwareKeyDown(ev, field))
            ctrl.onHardwareKeyUp(ev, field)
        }
        assertEquals("đi", field.localText)
        assertEquals("đi", field.remoteText)

        val bs = HwKeyEvent(keyCode = ImeSessionController.KEYCODE_DEL, unicodeChar = 0)
        assertTrue(ctrl.onHardwareKeyDown(bs, field)); ctrl.onHardwareKeyUp(bs, field)
        assertEquals("đ", field.localText)
        assertEquals("đ", field.remoteText)

        assertTrue(ctrl.onHardwareKeyDown(bs, field)); ctrl.onHardwareKeyUp(bs, field)
        assertEquals("d", field.localText)
        assertEquals("d", field.remoteText)

        assertTrue(ctrl.onHardwareKeyDown(bs, field)); ctrl.onHardwareKeyUp(bs, field)
        assertEquals("", field.localText)
        assertEquals("", field.remoteText)

        // Idle backspace passes through
        assertFalse(ctrl.onHardwareKeyDown(bs, field))

        // Type "a4" -> stays raw "a4", snapshot altDisplay == "ã"
        for (ch in "a4") {
            val ev = HwKeyEvent(keyCode = 30, unicodeChar = ch.code)
            assertTrue(ctrl.onHardwareKeyDown(ev, field))
            ctrl.onHardwareKeyUp(ev, field)
        }
        assertEquals("a4", field.localText)
        assertEquals("ã", lastSnapshot.altDisplay)

        // Press Esc -> flips to "ã" and learns!
        val esc = HwKeyEvent(keyCode = ImeSessionController.KEYCODE_ESCAPE, unicodeChar = 0)
        assertTrue(ctrl.onHardwareKeyDown(esc, field))
        ctrl.onHardwareKeyUp(esc, field)
        assertEquals("ã", field.localText)
        assertEquals("ã", field.remoteText)

        // Press Esc again -> flips back to raw "a4" and restores learned default!
        assertTrue(ctrl.onHardwareKeyDown(esc, field))
        ctrl.onHardwareKeyUp(esc, field)
        assertEquals("a4", field.localText)
        assertEquals("a4", field.remoteText)
    }

    @Test
    fun ctrlShiftTogglesVnAndShortcutsPassThrough() {
        val ctrl = newController(VgoSettings(hardwareMethod = InputMethodMode.VNI))
        val field = FakeAndroidAndRustDeskField()

        // Ctrl+Shift -> disables VN
        assertFalse(ctrl.onHardwareKeyDown(HwKeyEvent(CtrlShiftTracker.KEYCODE_CTRL_LEFT, 0, ctrlPressed = true), field))
        assertFalse(ctrl.onHardwareKeyDown(HwKeyEvent(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, 0, ctrlPressed = true, shiftPressed = true), field))
        ctrl.onHardwareKeyUp(HwKeyEvent(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, 0, ctrlPressed = true), field)
        ctrl.onHardwareKeyUp(HwKeyEvent(CtrlShiftTracker.KEYCODE_CTRL_LEFT, 0), field)

        assertEquals(listOf(false to InputMethodMode.VNI), toggles)
        assertFalse(ctrl.currentSettings().vnEnabled)

        // Typing "tie6ng1" with VN disabled passes raw keys through.
        typeHardwareString(ctrl, field, "tie6ng1")
        assertEquals("tie6ng1", field.localText)

        // Ctrl+Shift again -> re-enables VNI
        ctrl.onHardwareKeyDown(HwKeyEvent(CtrlShiftTracker.KEYCODE_CTRL_LEFT, 0, ctrlPressed = true), field)
        ctrl.onHardwareKeyDown(HwKeyEvent(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, 0, ctrlPressed = true, shiftPressed = true), field)
        ctrl.onHardwareKeyUp(HwKeyEvent(CtrlShiftTracker.KEYCODE_CTRL_LEFT, 0, shiftPressed = true), field)
        ctrl.onHardwareKeyUp(HwKeyEvent(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, 0), field)
        assertTrue(ctrl.currentSettings().vnEnabled)

        // Ctrl+C must finalize composing word and pass through (return false).
        val field2 = FakeAndroidAndRustDeskField()
        for (ch in "ta1") {
            val ev = HwKeyEvent(keyCode = 30, unicodeChar = ch.code)
            ctrl.onHardwareKeyDown(ev, field2)
            ctrl.onHardwareKeyUp(ev, field2)
        }
        assertEquals("tá", field2.localText)
        assertFalse(ctrl.onHardwareKeyDown(HwKeyEvent(keyCode = 31, unicodeChar = 'c'.code, ctrlPressed = true), field2))
        assertTrue(bridge.isEmpty())
    }

    @Test
    fun passwordFieldBypassesEngineCompletely() {
        val ctrl = newController(VgoSettings(hardwareMethod = InputMethodMode.VNI))
        val field = FakeAndroidAndRustDeskField()
        ctrl.onStartInput(EditorPolicy(packageName = "com.bank.app", bypassVietnamese = true, isPassword = true))

        typeHardwareString(ctrl, field, "tie6ng1")
        assertEquals("tie6ng1", field.localText)
        assertTrue(bridge.isEmpty())
    }

    @Test
    fun telexAutocorrectRevertWithEmojiBeforeCursor() {
        val ctrl = newController(VgoSettings(hardwareMethod = InputMethodMode.TELEX))
        val field = FakeAndroidAndRustDeskField()
        field.commitText("😀 ", 1)

        // "ccofn " auto-corrects to "còn "
        for (ch in "ccofn ") {
            val kc = if (ch == ' ') ImeSessionController.KEYCODE_SPACE else 30
            val ev = HwKeyEvent(keyCode = kc, unicodeChar = ch.code)
            val consumed = ctrl.onHardwareKeyDown(ev, field)
            ctrl.onHardwareKeyUp(ev, field)
            if (!consumed) field.injectRawKeyPassthrough(ch)
        }
        assertEquals("😀 còn ", field.localText)
        assertEquals("😀 còn ", field.remoteText)

        // Immediate Backspace reverts to "ccofn " without JNI UTF-8 crash on emoji!
        val bs = HwKeyEvent(keyCode = ImeSessionController.KEYCODE_DEL, unicodeChar = 0)
        assertTrue(ctrl.onHardwareKeyDown(bs, field))
        ctrl.onHardwareKeyUp(bs, field)
        assertEquals("😀 ccofn ", field.localText)
        assertEquals("😀 ccofn ", field.remoteText)
    }

    @Test
    fun composingFallbackExceptionListAndCursorJumpResetWorkInNoUnderlineMode() {
        // App listed in composingFallbackApps must fall back to setComposingText.
        val ctrl = newController(
            VgoSettings(
                softMethod = InputMethodMode.VNI,
                noUnderline = true,
                composingFallbackApps = setOf("com.quirky.app"),
            )
        )
        ctrl.onStartInput(EditorPolicy(packageName = "com.quirky.app", initialSelEnd = 0))
        assertFalse(ctrl.isUsingNoUnderline)

        // Normal app uses no-underline diff mode without calling getTextBeforeCursor while typing!
        ctrl.onStartInput(EditorPolicy(packageName = "com.normal.app", initialSelEnd = 0))
        assertTrue(ctrl.isUsingNoUnderline)

        var readCalls = 0
        val buf = StringBuilder()
        val spyBridge = object : TextBridge {
            override fun beginBatchEdit() = Unit
            override fun endBatchEdit() = Unit
            override fun setComposingText(text: CharSequence, newCursorPosition: Int) = error("Unexpected composing call")
            override fun finishComposingText() = Unit
            override fun commitText(text: CharSequence, newCursorPosition: Int) { buf.append(text) }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
                val d = beforeLength.coerceAtMost(buf.length)
                if (d > 0) buf.delete(buf.length - d, buf.length)
            }
            override fun getTextBeforeCursor(n: Int): String {
                readCalls++
                return buf.takeLast(n).toString()
            }
            override fun sendDownUpKeyEvent(keyCode: Int) = Unit
            override fun performEditorAction(imeAction: Int): Boolean = false
        }

        // Type "tie6" -> "tiê" with zero getTextBeforeCursor reads!
        for (ch in "tie6") {
            ctrl.onSoftText(ch.toString(), spyBridge)
            ctrl.onUpdateSelection(buf.length - 1, buf.length - 1, buf.length, buf.length, -1, -1, spyBridge)
        }
        assertEquals("tiê", buf.toString())
        assertEquals(0, readCalls)

        // Simulate external cursor jump to position 0 -> resets active word state cleanly!
        ctrl.onUpdateSelection(3, 3, 0, 0, -1, -1, spyBridge)
        assertTrue(bridge.isEmpty())
    }

    @Test
    fun shiftEnterInMultilineFieldBreaksLineInsteadOfSendingEnter() {
        val ctrl = newController(VgoSettings())
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.chat", isMultiline = true))
        val field = FakeAndroidAndRustDeskField()
        for (ch in "ok") ctrl.onSoftText(ch.toString(), field)
        ctrl.onSoftEnter(field, newline = true)
        assertEquals("ok\n", field.localText)
        assertEquals(0, field.enterKeys)   // no Enter key: the chat app would send the message
        ctrl.onSoftEnter(field)
        assertEquals(1, field.enterKeys)
    }

    @Test
    fun softTelexDoubleToneKeyUndoesToneLikeUniKey() {
        val ctrl = newController(VgoSettings())
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.discuss"))
        // keys -> text: the doubled tone key only removes the tone ("diss" -> "dis"),
        // English dictionary words keep every key.
        val cases = listOf(
            "diss " to "dis ", "disscuss " to "discuss ", "discuss " to "discuss ",
            "miss " to "miss ", "boss " to "boss ", "class " to "class ",
        )
        for ((keys, want) in cases) {
            val field = FakeAndroidAndRustDeskField()
            for (ch in keys) ctrl.onSoftText(ch.toString(), field)
            assertEquals(keys, want, field.localText)
        }
    }

    @Test
    fun perSourceTypingMethodDefaultsSoftTelexAndHardwareVniAndCommitsMidWord() {
        // Fresh defaults: soft = Telex, hardware = VNI
        val defaults = VgoSettings()
        assertEquals(InputMethodMode.TELEX, defaults.softMethod)
        assertEquals(InputMethodMode.VNI, defaults.hardwareMethod)

        val ctrl = newController(defaults)
        val field = FakeAndroidAndRustDeskField()
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.sources"))

        // 1. Soft touch keyboard uses Telex by default: "tieengs " -> "tiếng "
        for (ch in "tieengs ") {
            ctrl.onSoftText(ch.toString(), field)
        }
        assertEquals(InputSource.SOFT, ctrl.activeSource())
        assertEquals(InputMethodMode.TELEX, ctrl.activeMethod())
        assertEquals("tiếng ", field.localText)

        // 2. Physical keyboard uses VNI by default without changing any setting: "Vie65t " -> "Việt "
        typeHardwareString(ctrl, field, "Vie65t ")
        assertEquals(InputSource.HARDWARE, ctrl.activeSource())
        assertEquals(InputMethodMode.VNI, ctrl.activeMethod())
        assertEquals("tiếng Việt ", field.localText)
        assertEquals("tiếng Việt ", field.remoteText)

        // 3. Switch source MID-WORD (soft -> hardware): must commit composing word first!
        val fieldMid = FakeAndroidAndRustDeskField()
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.midword"))
        // Start "tiee" on soft (Telex) -> composing "tiê"
        for (ch in "tiee") {
            ctrl.onSoftText(ch.toString(), fieldMid)
        }
        assertEquals("tiê", fieldMid.localText)
        assertFalse(bridge.isEmpty())

        // Now press 'a' then '1' on hardware keyboard (VNI): must commit "tiê" first, then compose "á" -> "tiêá"
        for (ch in "a1") {
            val ev = HwKeyEvent(keyCode = 29, unicodeChar = ch.code)
            assertTrue(ctrl.onHardwareKeyDown(ev, fieldMid))
            ctrl.onHardwareKeyUp(ev, fieldMid)
        }
        assertEquals(InputSource.HARDWARE, ctrl.activeSource())
        assertEquals("tiêá", fieldMid.localText)
        assertEquals("tiêá", fieldMid.remoteText)

        // Now switch back MID-WORD (hardware -> soft): start "ba1" ("bá") on hardware, then tap "as" on soft -> "báá"
        val fieldMid2 = FakeAndroidAndRustDeskField()
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.midword2"))
        for (ch in "ba1") {
            val ev = HwKeyEvent(keyCode = 30, unicodeChar = ch.code)
            assertTrue(ctrl.onHardwareKeyDown(ev, fieldMid2))
            ctrl.onHardwareKeyUp(ev, fieldMid2)
        }
        assertEquals("bá", fieldMid2.localText)
        for (ch in "as") {
            ctrl.onSoftText(ch.toString(), fieldMid2)
        }
        assertEquals(InputSource.SOFT, ctrl.activeSource())
        assertEquals("báá", fieldMid2.localText)
        assertEquals("báá", fieldMid2.remoteText)

        // 4. Changing active source's method (e.g. menu changes SOFT -> VNI) leaves HARDWARE untouched!
        val updated = ctrl.currentSettings().copy(softMethod = InputMethodMode.VNI)
        ctrl.applySettings(updated, fieldMid2)
        assertEquals(InputMethodMode.VNI, ctrl.currentSettings().softMethod)
        assertEquals(InputMethodMode.VNI, ctrl.currentSettings().hardwareMethod)

        val updated2 = ctrl.currentSettings().copy(hardwareMethod = InputMethodMode.TELEX)
        ctrl.applySettings(updated2, fieldMid2)
        assertEquals(InputMethodMode.VNI, ctrl.currentSettings().softMethod)
        assertEquals(InputMethodMode.TELEX, ctrl.currentSettings().hardwareMethod)

        // Verify soft now types VNI ("tie6ng1") while hardware now types Telex ("Vieejt")
        val fieldSwap = FakeAndroidAndRustDeskField()
        ctrl.onStartInput(EditorPolicy(packageName = "dev.vgo.test.swap"))
        for (ch in "tie6ng1 ") ctrl.onSoftText(ch.toString(), fieldSwap)
        typeHardwareString(ctrl, fieldSwap, "Vieejt")
        assertEquals("tiếng Việt", fieldSwap.localText)
    }
}
