package dev.vgo.ime.ime

import android.view.inputmethod.EditorInfo
import dev.vgo.ime.engine.EngineSnapshot
import dev.vgo.ime.engine.InputMethodMode
import dev.vgo.ime.engine.VgoEngineBridge
import dev.vgo.ime.prefs.HwToggleShortcut
import dev.vgo.ime.prefs.InputSource
import dev.vgo.ime.prefs.VgoSettings

data class HwKeyEvent(
    val keyCode: Int,
    val unicodeChar: Int,
    val ctrlPressed: Boolean = false,
    val altPressed: Boolean = false,
    val metaPressed: Boolean = false,
    val shiftPressed: Boolean = false,
    val isNumpad: Boolean = false,
    val repeatCount: Int = 0,
)

interface TextBridge {
    fun beginBatchEdit()
    fun endBatchEdit()
    fun setComposingText(text: CharSequence, newCursorPosition: Int)
    fun finishComposingText()
    fun commitText(text: CharSequence, newCursorPosition: Int)
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int)
    fun getTextBeforeCursor(n: Int): String
    fun sendDownUpKeyEvent(keyCode: Int)
    fun performEditorAction(imeAction: Int): Boolean
}

interface SessionCallbacks {
    fun onSnapshotChanged(snapshot: EngineSnapshot)
    fun onVnEnabledToggled(enabled: Boolean, method: InputMethodMode)
}

class ImeSessionController(
    private val engine: VgoEngineBridge,
    private val callbacks: SessionCallbacks,
) {
    private val ctrlShiftTracker = CtrlShiftTracker()
    private val consumedKeyCodes = mutableSetOf<Int>()

    private var settings: VgoSettings = VgoSettings()
    private var activeSource: InputSource = InputSource.SOFT
    private var engineMethod: InputMethodMode? = null
    private var configApplied = false
    private var policy: EditorPolicy = EditorPolicy()
    private var composingActive = false
    private var lastKnownCandStart = -1

    // Pure IME-side state for no-underline diff mode (never reads text back from editor while typing).
    private var shownText: String = ""
    private var expectedCursor: Int = -1
    private var pendingSelfUpdates: Int = 0

    val isUsingNoUnderline: Boolean
        get() = settings.noUnderline && policy.packageName !in settings.composingFallbackApps

    fun activeSource(): InputSource = activeSource
    fun activeMethod(): InputMethodMode = settings.methodFor(activeSource)

    fun setActiveSourceIfEmpty(source: InputSource) {
        if (activeSource == source && configApplied && engineMethod == settings.methodFor(source)) return
        if (!engine.isEmpty()) return
        activeSource = source
        val desired = settings.methodFor(source)
        if (!configApplied || engineMethod != desired) {
            configApplied = true
            engineMethod = desired
            engine.setConfig(desired, settings.oldStyleTone, settings.wAlwaysU)
            publishSnapshot()
        }
    }

    private fun ensureSource(source: InputSource, bridge: TextBridge?) {
        val sourceChanged = activeSource != source
        if (sourceChanged && !engine.isEmpty()) {
            finalizeComposing(bridge, 0)
        }
        activeSource = source
        val desiredMethod = settings.methodFor(source)
        if (!configApplied || engineMethod != desiredMethod) {
            if (configApplied && !engine.isEmpty()) {
                finalizeComposing(bridge, 0)
            }
            configApplied = true
            engineMethod = desiredMethod
            engine.setConfig(desiredMethod, settings.oldStyleTone, settings.wAlwaysU)
            publishSnapshot()
        }
    }

    fun applySettings(newSettings: VgoSettings, bridge: TextBridge?) {
        val prevNoUnderline = isUsingNoUnderline
        val targetMethod = newSettings.methodFor(activeSource)
        val methodChanged = engineMethod != targetMethod
        val engineConfigChanged = !configApplied || methodChanged ||
            settings.oldStyleTone != newSettings.oldStyleTone ||
            settings.wAlwaysU != newSettings.wAlwaysU
        val vnDisabled = settings.vnEnabled && !newSettings.vnEnabled
        val nextNoUnderline = newSettings.noUnderline && policy.packageName !in newSettings.composingFallbackApps
        val renderModeChanged = prevNoUnderline != nextNoUnderline

        if (configApplied && (engineConfigChanged || renderModeChanged || vnDisabled) && !engine.isEmpty()) {
            finalizeComposing(bridge, 0)
        }

        settings = newSettings
        if (engineConfigChanged) {
            configApplied = true
            engineMethod = targetMethod
            engine.setConfig(targetMethod, newSettings.oldStyleTone, newSettings.wAlwaysU)
            publishSnapshot()
        }
    }

    fun currentSettings(): VgoSettings = settings
    fun currentPolicy(): EditorPolicy = policy

    fun onStartInput(newPolicy: EditorPolicy) {
        policy = newPolicy
        composingActive = false
        lastKnownCandStart = -1
        shownText = ""
        expectedCursor = newPolicy.initialSelEnd
        pendingSelfUpdates = 0
        consumedKeyCodes.clear()
        ctrlShiftTracker.reset()
        engine.reset()
        engine.setApp(if (newPolicy.isPassword) "" else newPolicy.packageName)
        publishSnapshot()
    }

    fun onFinishInput() {
        composingActive = false
        lastKnownCandStart = -1
        shownText = ""
        expectedCursor = -1
        pendingSelfUpdates = 0
        consumedKeyCodes.clear()
        ctrlShiftTracker.reset()
        engine.reset()
        publishSnapshot()
    }

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        bridge: TextBridge?,
    ) {
        if (isUsingNoUnderline) {
            if (newSelStart != newSelEnd) {
                // User selected a range -> reset active word state.
                if (shownText.isNotEmpty() || !engine.isEmpty()) {
                    shownText = ""
                    pendingSelfUpdates = 0
                    engine.reset()
                    publishSnapshot()
                }
                expectedCursor = -1
                return
            }

            if (shownText.isEmpty()) {
                expectedCursor = newSelStart
                pendingSelfUpdates = 0
                return
            }

            // Cursor matches what IME expects after its own diff edits -> keep composing word!
            if (expectedCursor >= 0 && newSelStart == expectedCursor) {
                if (pendingSelfUpdates > 0) pendingSelfUpdates--
                return
            }

            // First selection callback when initialSelEnd was unknown (-1): anchor cursor.
            if (expectedCursor < 0 && pendingSelfUpdates > 0) {
                expectedCursor = newSelStart
                pendingSelfUpdates--
                return
            }

            // Transient mid-batch delete/commit callback in WebViews while our own diff is in flight.
            if (pendingSelfUpdates > 0 && expectedCursor >= 0 &&
                kotlin.math.abs(newSelStart - expectedCursor) <= shownText.length + 2
            ) {
                pendingSelfUpdates--
                return
            }

            // External cursor jump (tap/mouse/arrow): reset active word cleanly!
            shownText = ""
            pendingSelfUpdates = 0
            expectedCursor = newSelStart
            engine.reset()
            publishSnapshot()
            return
        }

        expectedCursor = if (newSelStart == newSelEnd) newSelStart else -1

        if (!composingActive) {
            lastKnownCandStart = candidatesStart
            return
        }

        val jumpedOutside = when {
            candidatesStart >= 0 -> {
                newSelStart != newSelEnd ||
                    newSelStart < candidatesStart ||
                    newSelStart > candidatesEnd
            }
            lastKnownCandStart >= 0 -> true
            newSelStart != newSelEnd -> true
            else -> false
        }

        lastKnownCandStart = candidatesStart

        if (jumpedOutside) {
            composingActive = false
            bridge?.finishComposingText()
            engine.reset()
            publishSnapshot()
        }
    }

    fun onHardwareKeyDown(event: HwKeyEvent, bridge: TextBridge?): Boolean {
        if (settings.hwToggleShortcut == HwToggleShortcut.CTRL_SHIFT) {
            ctrlShiftTracker.onKeyDown(event.keyCode, event.altPressed, event.metaPressed)
        }

        if (isModifierKey(event.keyCode)) {
            return false
        }

        if (event.keyCode == KEYCODE_SPACE && event.repeatCount == 0 && !event.altPressed && !event.metaPressed) {
            val triggerShiftSpace = settings.hwToggleShortcut == HwToggleShortcut.SHIFT_SPACE &&
                event.shiftPressed && !event.ctrlPressed
            val triggerCtrlSpace = settings.hwToggleShortcut == HwToggleShortcut.CTRL_SPACE &&
                event.ctrlPressed && !event.shiftPressed
            if (triggerShiftSpace || triggerCtrlSpace) {
                ensureSource(InputSource.HARDWARE, bridge)
                toggleVnEnabled(bridge)
                consumedKeyCodes.add(event.keyCode)
                return true
            }
        }

        if (policy.bypassVietnamese || !settings.vnEnabled || bridge == null) {
            return false
        }

        // Switch source to HARDWARE before processing non-modifier keys; commits any mid-word soft composing first.
        if (event.keyCode != KEYCODE_ESCAPE && event.keyCode != KEYCODE_DEL) {
            ensureSource(InputSource.HARDWARE, bridge)
        }

        if (event.keyCode != KEYCODE_DEL) {
            engine.clearCanRevert()
        }

        if (event.keyCode == KEYCODE_ESCAPE && !event.ctrlPressed && !event.altPressed && !event.metaPressed) {
            if (!engine.isEmpty() && engine.flipAndLearn()) {
                renderWord(bridge, engine.snapshot())
                consumedKeyCodes.add(event.keyCode)
                return true
            }
            return false
        }

        if (event.ctrlPressed || event.altPressed || event.metaPressed) {
            if (!engine.isEmpty()) {
                finalizeComposing(bridge, 0)
            } else {
                engine.clearContext()
            }
            return false
        }

        if (event.keyCode == KEYCODE_DEL) {
            if (handleBackspaceInternal(bridge)) {
                consumedKeyCodes.add(event.keyCode)
                return true
            }
            return false
        }

        if (isNavigationOrControlKey(event.keyCode)) {
            if (!engine.isEmpty()) {
                finalizeComposing(bridge, 0)
            } else {
                engine.clearContext()
            }
            return false
        }

        val cp = event.unicodeChar
        if (cp <= 0) {
            return false
        }

        if (cp <= 0x7F && engine.acceptsChar(cp.toChar(), event.isNumpad)) {
            val snap = engine.key(cp.toChar())
            renderWord(bridge, snap)
            consumedKeyCodes.add(event.keyCode)
            return true
        }

        val printable = cp >= 0x20 && cp != 0x7F
        val boundary = if (printable) cp else 0
        if (!engine.isEmpty()) {
            val boundaryText = if (printable) String(Character.toChars(cp)) else null
            bridge.beginBatchEdit()
            try {
                if (!isUsingNoUnderline) {
                    bridge.finishComposingText()
                }
                if (boundaryText != null) {
                    bridge.commitText(boundaryText, 1)
                    advanceExpectedCursor(boundaryText.length)
                }
            } finally {
                bridge.endBatchEdit()
            }
            composingActive = false
            lastKnownCandStart = -1
            shownText = ""
            pendingSelfUpdates = 0
            engine.finalizeWord(boundary)
            publishSnapshot()
            if (boundaryText != null) {
                consumedKeyCodes.add(event.keyCode)
                return true
            }
            return false
        }

        engine.finalizeWord(boundary)
        return false
    }

    fun onHardwareKeyUp(event: HwKeyEvent, bridge: TextBridge?): Boolean {
        if (settings.hwToggleShortcut == HwToggleShortcut.CTRL_SHIFT) {
            if (ctrlShiftTracker.onKeyUp(event.keyCode)) {
                ensureSource(InputSource.HARDWARE, bridge)
                toggleVnEnabled(bridge)
            }
        }
        return consumedKeyCodes.remove(event.keyCode)
    }

    // ---- Soft keyboard operations ----

    fun onSoftText(text: String, bridge: TextBridge?, isNumpad: Boolean = false) {
        if (bridge == null || text.isEmpty()) return
        ensureSource(InputSource.SOFT, bridge)
        engine.clearCanRevert()

        val canUseEngine = !policy.bypassVietnamese && settings.vnEnabled && text.length == 1
        if (canUseEngine) {
            val ch = text[0]
            if (ch.code <= 0x7F && engine.acceptsChar(ch, isNumpad)) {
                val snap = engine.key(ch)
                renderWord(bridge, snap)
                return
            }
        }

        val cp = text.codePointAt(0)
        val boundary = if (text.codePointCount(0, text.length) == 1 && cp >= 0x20 && cp != 0x7F) cp else 0

        if (!engine.isEmpty()) {
            bridge.beginBatchEdit()
            try {
                if (!isUsingNoUnderline) {
                    bridge.finishComposingText()
                }
                bridge.commitText(text, 1)
                advanceExpectedCursor(text.length)
            } finally {
                bridge.endBatchEdit()
            }
            composingActive = false
            lastKnownCandStart = -1
            shownText = ""
            pendingSelfUpdates = 0
            engine.finalizeWord(boundary)
            publishSnapshot()
        } else {
            bridge.commitText(text, 1)
            advanceExpectedCursor(text.length)
            if (!policy.bypassVietnamese) {
                engine.finalizeWord(boundary)
            }
        }
    }

    fun onSoftBackspace(bridge: TextBridge?) {
        if (bridge == null) return
        if (!policy.bypassVietnamese && settings.vnEnabled && handleBackspaceInternal(bridge)) {
            return
        }
        bridge.deleteSurroundingText(1, 0)
        if (expectedCursor > 0) expectedCursor--
    }

    fun onSoftDeleteWord(bridge: TextBridge?) {
        if (bridge == null) return
        if (!engine.isEmpty()) {
            if (isUsingNoUnderline) {
                renderDiff(bridge, "")
            } else {
                bridge.beginBatchEdit()
                try {
                    bridge.setComposingText("", 1)
                    bridge.finishComposingText()
                } finally {
                    bridge.endBatchEdit()
                }
            }
            composingActive = false
            lastKnownCandStart = -1
            shownText = ""
            pendingSelfUpdates = 0
            engine.reset()
            publishSnapshot()
            return
        }
        val before = bridge.getTextBeforeCursor(64)
        if (before.isEmpty()) return
        var idx = before.length
        while (idx > 0 && before[idx - 1].isWhitespace()) idx--
        while (idx > 0 && !before[idx - 1].isWhitespace()) idx--
        val deleteCount = (before.length - idx).coerceAtLeast(1)
        bridge.deleteSurroundingText(deleteCount, 0)
        if (expectedCursor >= deleteCount) expectedCursor -= deleteCount else expectedCursor = -1
        engine.clearContext()
    }

    fun onSoftFlip(bridge: TextBridge?) {
        if (bridge == null || policy.bypassVietnamese || !settings.vnEnabled) return
        if (!engine.isEmpty() && engine.flipAndLearn()) {
            renderWord(bridge, engine.snapshot())
        }
    }

    /** [newline]: the user held Shift — break the line instead of Enter, which chat apps
     *  (PPM, Slack, web chats) treat as "send" while Shift+Enter means a new line. */
    fun onSoftEnter(bridge: TextBridge?, newline: Boolean = false) {
        if (bridge == null) return
        if (newline && policy.isMultiline) {
            onSoftText("\n", bridge)
            return
        }
        if (!engine.isEmpty()) {
            finalizeComposing(bridge, 0)
        } else {
            engine.clearContext()
        }
        if (policy.isMultiline || policy.imeAction == EditorInfo.IME_ACTION_NONE ||
            policy.imeAction == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            bridge.sendDownUpKeyEvent(KEYCODE_ENTER)
        } else {
            if (!bridge.performEditorAction(policy.imeAction)) {
                bridge.sendDownUpKeyEvent(KEYCODE_ENTER)
            }
        }
    }

    fun onSoftTab(bridge: TextBridge?) {
        if (bridge == null) return
        if (!engine.isEmpty()) {
            finalizeComposing(bridge, 0)
        } else {
            engine.clearContext()
        }
        bridge.sendDownUpKeyEvent(KEYCODE_TAB)
    }

    fun finalizeComposing(bridge: TextBridge?, boundary: Int = 0) {
        if (!engine.isEmpty()) {
            if (!isUsingNoUnderline) {
                bridge?.finishComposingText()
            }
            composingActive = false
            lastKnownCandStart = -1
            shownText = ""
            pendingSelfUpdates = 0
            engine.finalizeWord(boundary)
            publishSnapshot()
        } else if (boundary == 0) {
            engine.clearContext()
        }
    }

    /**
     * Gboard suggestion tap: replace the word being typed (if any) with [word] followed by a space.
     * In no-underline mode the typed word length comes from IME state; otherwise from the editor text.
     */
    fun applySuggestion(bridge: TextBridge?, word: String) {
        if (bridge == null || word.isEmpty()) return
        bridge.beginBatchEdit()
        try {
            if (!engine.isEmpty() && !isUsingNoUnderline && composingActive) {
                bridge.setComposingText(word, 1)
                bridge.finishComposingText()
                bridge.commitText(" ", 1)
            } else {
                val typedLen = if (!engine.isEmpty() && isUsingNoUnderline) {
                    shownText.length
                } else {
                    dev.vgo.ime.predict.PredictContext.trailingWordLength(bridge.getTextBeforeCursor(64))
                }
                if (typedLen > 0) bridge.deleteSurroundingText(typedLen, 0)
                bridge.commitText("$word ", 1)
                advanceExpectedCursor(word.length + 1 - typedLen)
            }
        } finally {
            bridge.endBatchEdit()
        }
        composingActive = false
        lastKnownCandStart = -1
        shownText = ""
        pendingSelfUpdates = 0
        engine.reset()
        publishSnapshot()
    }

    fun toggleVnEnabled(bridge: TextBridge?) {
        if (!engine.isEmpty()) {
            finalizeComposing(bridge, 0)
        }
        val next = !settings.vnEnabled
        settings = settings.copy(vnEnabled = next)
        callbacks.onVnEnabledToggled(next, activeMethod())
        publishSnapshot()
    }

    private fun handleBackspaceInternal(bridge: TextBridge): Boolean {
        if (engine.backspace()) {
            val snap = engine.snapshot()
            if (isUsingNoUnderline) {
                renderDiff(bridge, if (snap.empty) "" else snap.display)
            } else if (snap.empty) {
                bridge.beginBatchEdit()
                try {
                    bridge.setComposingText("", 1)
                    bridge.finishComposingText()
                } finally {
                    bridge.endBatchEdit()
                }
                composingActive = false
                lastKnownCandStart = -1
            } else {
                bridge.setComposingText(snap.display, 1)
                composingActive = true
            }
            publishSnapshot(snap)
            return true
        }

        val before = bridge.getTextBeforeCursor(64)
        val revert = engine.tryRevertCorrection(before)
        if (revert.reverted) {
            bridge.beginBatchEdit()
            try {
                bridge.deleteSurroundingText(revert.committedToDelete.length, 0)
                bridge.commitText(revert.replacement, 1)
                advanceExpectedCursor(revert.replacement.length - revert.committedToDelete.length)
            } finally {
                bridge.endBatchEdit()
            }
            composingActive = false
            lastKnownCandStart = -1
            shownText = ""
            pendingSelfUpdates = 0
            publishSnapshot()
            return true
        }

        engine.clearContext()
        return false
    }

    private fun renderWord(bridge: TextBridge, snap: EngineSnapshot) {
        if (isUsingNoUnderline) {
            renderDiff(bridge, if (snap.empty) "" else snap.display)
        } else {
            bridge.setComposingText(snap.display, 1)
            composingActive = !snap.empty
        }
        publishSnapshot(snap)
    }

    /**
     * Diff-overwrite without SPAN_COMPOSING (no underline), matching fcitx5/vgoime.cpp.
     * Tracks word state and expectedCursor purely in IME memory without reading editor text.
     */
    private fun renderDiff(bridge: TextBridge, next: String) {
        if (shownText == next) return

        var prefix = 0
        val maxPrefix = minOf(shownText.length, next.length)
        while (prefix < maxPrefix && shownText[prefix] == next[prefix]) {
            prefix++
        }
        if (prefix > 0 && prefix < maxPrefix && Character.isHighSurrogate(shownText[prefix - 1])) {
            prefix--
        }

        var toDelete = shownText.length - prefix
        val suffix = next.substring(prefix)

        if (toDelete > 0 && expectedCursor >= 0) {
            toDelete = toDelete.coerceAtMost(expectedCursor)
        }

        if (toDelete > 0 || suffix.isNotEmpty()) {
            pendingSelfUpdates++
            advanceExpectedCursor(suffix.length - toDelete)
            bridge.beginBatchEdit()
            try {
                if (toDelete > 0) {
                    bridge.deleteSurroundingText(toDelete, 0)
                }
                if (suffix.isNotEmpty()) {
                    bridge.commitText(suffix, 1)
                }
            } finally {
                bridge.endBatchEdit()
            }
        }
        shownText = next
    }

    private fun advanceExpectedCursor(delta: Int) {
        if (expectedCursor >= 0) {
            expectedCursor = (expectedCursor + delta).coerceAtLeast(0)
        }
    }

    private fun publishSnapshot(snap: EngineSnapshot = engine.snapshot()) {
        callbacks.onSnapshotChanged(snap)
    }

    private fun isModifierKey(keyCode: Int): Boolean = keyCode in setOf(
        CtrlShiftTracker.KEYCODE_SHIFT_LEFT,
        CtrlShiftTracker.KEYCODE_SHIFT_RIGHT,
        CtrlShiftTracker.KEYCODE_CTRL_LEFT,
        CtrlShiftTracker.KEYCODE_CTRL_RIGHT,
        KEYCODE_ALT_LEFT,
        KEYCODE_ALT_RIGHT,
        KEYCODE_META_LEFT,
        KEYCODE_META_RIGHT,
        KEYCODE_CAPS_LOCK,
        KEYCODE_NUM_LOCK,
        KEYCODE_SCROLL_LOCK,
        KEYCODE_FUNCTION,
    )

    private fun isNavigationOrControlKey(keyCode: Int): Boolean = keyCode in setOf(
        KEYCODE_ENTER,
        KEYCODE_NUMPAD_ENTER,
        KEYCODE_TAB,
        KEYCODE_DPAD_UP,
        KEYCODE_DPAD_DOWN,
        KEYCODE_DPAD_LEFT,
        KEYCODE_DPAD_RIGHT,
        KEYCODE_DPAD_CENTER,
        KEYCODE_MOVE_HOME,
        KEYCODE_MOVE_END,
        KEYCODE_PAGE_UP,
        KEYCODE_PAGE_DOWN,
        KEYCODE_FORWARD_DEL,
        KEYCODE_INSERT,
        KEYCODE_ESCAPE,
    ) || keyCode in KEYCODE_F1..KEYCODE_F12

    companion object {
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_ALT_LEFT = 57
        const val KEYCODE_ALT_RIGHT = 58
        const val KEYCODE_TAB = 61
        const val KEYCODE_SPACE = 62
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
        const val KEYCODE_PAGE_UP = 92
        const val KEYCODE_PAGE_DOWN = 93
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_FORWARD_DEL = 112
        const val KEYCODE_CAPS_LOCK = 115
        const val KEYCODE_SCROLL_LOCK = 116
        const val KEYCODE_META_LEFT = 117
        const val KEYCODE_META_RIGHT = 118
        const val KEYCODE_FUNCTION = 119
        const val KEYCODE_MOVE_HOME = 122
        const val KEYCODE_MOVE_END = 123
        const val KEYCODE_INSERT = 124
        const val KEYCODE_F1 = 131
        const val KEYCODE_F12 = 142
        const val KEYCODE_NUM_LOCK = 143
        const val KEYCODE_NUMPAD_ENTER = 160
    }
}
