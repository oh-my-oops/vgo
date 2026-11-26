package dev.vgo.ime

import android.content.ClipDescription
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.vgo.ime.clipboard.ClipboardEntry
import dev.vgo.ime.clipboard.LocalClipboardHistory
import dev.vgo.ime.engine.AssetDataInstaller
import dev.vgo.ime.engine.EngineSnapshot
import dev.vgo.ime.engine.InputMethodMode
import dev.vgo.ime.engine.NativeVgoEngineBridge
import dev.vgo.ime.ime.EditorPolicy
import dev.vgo.ime.ime.HwKeyEvent
import dev.vgo.ime.ime.ImeSessionController
import dev.vgo.ime.ime.InlineAutofillHelper
import dev.vgo.ime.ime.InlineAutofillState
import dev.vgo.ime.ime.SessionCallbacks
import dev.vgo.ime.ime.TextBridge
import dev.vgo.ime.predict.PredictContext
import dev.vgo.ime.predict.PredictionManager
import dev.vgo.ime.prefs.InputSource
import dev.vgo.ime.prefs.VgoPreferences
import dev.vgo.ime.settings.SettingsActivity
import dev.vgo.ime.ui.ActiveAuxPanel
import dev.vgo.ime.ui.KeyAction
import dev.vgo.ime.ui.KeyboardPage
import dev.vgo.ime.ui.KeyboardTheme
import dev.vgo.ime.ui.ShiftState
import dev.vgo.ime.ui.VgoClipboardPanel
import dev.vgo.ime.ui.VgoEmojiPanel
import dev.vgo.ime.ui.VgoInputContainer
import dev.vgo.ime.ui.VgoKeyboardView
import dev.vgo.ime.ui.VgoMenuPanel
import dev.vgo.ime.ui.VgoTextEditPanel
import dev.vgo.ime.ui.VgoToolbarView
import kotlin.math.abs

class VgoInputMethodService :
    InputMethodService(),
    SessionCallbacks,
    VgoToolbarView.Callbacks,
    VgoKeyboardView.KeyListener,
    VgoMenuPanel.Callbacks,
    VgoTextEditPanel.Callbacks {

    private lateinit var preferences: VgoPreferences
    private lateinit var engineBridge: NativeVgoEngineBridge
    private lateinit var controller: ImeSessionController
    private lateinit var clipboardHistory: LocalClipboardHistory
    private lateinit var predictions: PredictionManager
    private var currentSuggestions: List<String> = emptyList()
    private var lastPartialEmpty = true
    private var typedSinceRefresh = false
    private var hasSelection = false
    private val predictRunnable = Runnable { refreshPredictions() }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipChipExpiryRunnable = Runnable { renderSoftUi() }
    private val inlineAutofillState = InlineAutofillState()
    private var pendingAutofillRegular: List<View> = emptyList()
    private var pendingAutofillPinned: View? = null

    private var inputContainer: VgoInputContainer? = null
    private var menuPanel: VgoMenuPanel? = null
    private var emojiPanel: VgoEmojiPanel? = null
    private var clipboardPanel: VgoClipboardPanel? = null
    private var textEditPanel: VgoTextEditPanel? = null
    private var statusToast: Toast? = null

    private var currentPage: KeyboardPage = KeyboardPage.ALPHA
    private var shiftState: ShiftState = ShiftState.OFF
    private var lastSnapshot: EngineSnapshot = EngineSnapshot()
    private var lastShiftTapMs: Long = 0L
    private var lastSpaceTapMs: Long = 0L
    private var lastLearnedRevision: Long = 0L
    private var currentEditorInfo: EditorInfo? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val rev = preferences.learnedRevision()
        if (rev != lastLearnedRevision) {
            lastLearnedRevision = rev
            engineBridge.reloadUserFiles()
        }
        val updated = preferences.read()
        clipboardHistory.setEnabled(updated.clipboardEnabled)
        controller.applySettings(updated, activeTextBridge())
        inputContainer?.refreshTheme(updated.themeMode)
        renderSoftUi()
        renderAuxPanels()
    }

    override fun onCreate() {
        super.onCreate()
        preferences = VgoPreferences(this)
        lastLearnedRevision = preferences.learnedRevision()
        val dirs = AssetDataInstaller.ensureInstalled(this)
        engineBridge = NativeVgoEngineBridge().apply {
            init(dirs.baseDir.absolutePath, dirs.userDir.absolutePath)
        }
        controller = ImeSessionController(engineBridge, this)
        val initial = preferences.read()
        controller.applySettings(initial, null)
        predictions = PredictionManager(this, dirs.userDir) { schedulePredictions() }
        predictions.preload(if (initial.vnEnabled) "vi" else "en")

        clipboardHistory = LocalClipboardHistory(this) {
            scheduleClipExpiryRefresh()
            renderSoftUi()
            renderClipboardPanel()
        }
        clipboardHistory.setEnabled(initial.clipboardEnabled)
        clipboardHistory.start()

        preferences.registerListener(prefListener)
    }

    override fun onDestroy() {
        preferences.unregisterListener(prefListener)
        mainHandler.removeCallbacks(clipChipExpiryRunnable)
        mainHandler.removeCallbacks(predictRunnable)
        predictions.save()
        clipboardHistory.stop()
        statusToast?.cancel()
        statusToast = null
        super.onDestroy()
    }

    // Never hijack full screen in tablet landscape mode (matches Gboard tablet behavior).
    override fun onEvaluateFullscreenMode(): Boolean = false

    // Intentionally DO NOT override onEvaluateInputViewShown() so Android hides the soft keyboard
    // whenever a physical keyboard is active and show_ime_with_hard_keyboard == 0 (like Gboard).

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateInputViewShown()
        if (!isInputViewShown) {
            requestHideSelf(0)
        }
        inputContainer?.refreshTheme(controller.currentSettings().themeMode)
        renderSoftUi()
        renderAuxPanels()
        schedulePredictions()
    }

    override fun onCreateInputView(): View {
        val container = VgoInputContainer(
            context = this,
            toolbarCallbacks = this,
            keyListener = this,
        )
        val menu = VgoMenuPanel(this, this)
        val emoji = VgoEmojiPanel(
            context = this,
            onEmojiSelected = { em -> controller.onSoftText(em, activeTextBridge()) },
            onBackspace = { controller.onSoftBackspace(activeTextBridge()) },
            onClose = {
                container.setAuxPanel(ActiveAuxPanel.NONE)
                renderSoftUi()
            },
        )
        val clip = VgoClipboardPanel(
            context = this,
            onPaste = { entry ->
                pasteClipboardEntry(entry)
                container.setAuxPanel(ActiveAuxPanel.NONE)
                renderSoftUi()
            },
            onTogglePin = { id -> clipboardHistory.togglePin(id) },
            onDelete = { id -> clipboardHistory.remove(id) },
            onClearUnpinned = { clipboardHistory.clearUnpinned() },
            onToggleEnabled = { enabled ->
                preferences.setClipboardEnabled(enabled)
                clipboardHistory.setEnabled(enabled)
                renderClipboardPanel()
                renderSoftUi()
            },
            onClose = {
                container.setAuxPanel(ActiveAuxPanel.NONE)
                renderSoftUi()
            },
        )
        val textEdit = VgoTextEditPanel(this, this)

        container.setAuxPanels(menu, emoji, clip, textEdit)
        container.toolbarView.setVoiceAvailable(findSystemVoiceImeId() != null)
        container.refreshTheme(controller.currentSettings().themeMode)

        inputContainer = container
        menuPanel = menu
        emojiPanel = emoji
        clipboardPanel = clip
        textEditPanel = textEdit

        if (inlineAutofillState.active && (pendingAutofillRegular.isNotEmpty() || pendingAutofillPinned != null)) {
            container.toolbarView.setInlineAutofillViews(pendingAutofillRegular, pendingAutofillPinned)
        }

        renderSoftUi()
        renderAuxPanels()
        return container
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        val palette = inputContainer?.currentPalette()
            ?: KeyboardTheme.resolve(this, controller.currentSettings().themeMode)
        return InlineAutofillHelper.buildRequest(this, palette)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val epoch = inlineAutofillState.nextEpoch()
        pendingAutofillRegular = emptyList()
        pendingAutofillPinned = null
        inputContainer?.toolbarView?.clearInlineAutofill()

        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            renderSoftUi()
            return true
        }

        val (regularList, pinnedItem) = InlineAutofillState.partitionPinned(suggestions) { it.info.isPinned }
        val total = regularList.size + if (pinnedItem != null) 1 else 0
        val inflatedRegular = arrayOfNulls<View>(regularList.size)
        var inflatedPinned: View? = null
        var remaining = total
        val chipHeightPx = (36f * resources.displayMetrics.density).toInt()
        val inflateSize = Size(ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx)

        fun onOneDone() {
            remaining--
            if (remaining > 0) return
            val regViews = inflatedRegular.filterNotNull()
            val hasAny = regViews.isNotEmpty() || inflatedPinned != null
            if (!inlineAutofillState.acceptInflatedResult(epoch, hasAny)) return
            pendingAutofillRegular = regViews
            pendingAutofillPinned = inflatedPinned
            inputContainer?.toolbarView?.setInlineAutofillViews(regViews, inflatedPinned)
            renderSoftUi()
        }

        regularList.forEachIndexed { idx, sug ->
            sug.inflate(this, inflateSize, mainExecutor) { view ->
                if (epoch == inlineAutofillState.epoch) {
                    inflatedRegular[idx] = view
                    onOneDone()
                }
            }
        }
        if (pinnedItem != null) {
            pinnedItem.inflate(this, inflateSize, mainExecutor) { view ->
                if (epoch == inlineAutofillState.epoch) {
                    inflatedPinned = view
                    onOneDone()
                }
            }
        }
        return true
    }

    private fun clearAutofillSuggestions() {
        inlineAutofillState.clear()
        pendingAutofillRegular = emptyList()
        pendingAutofillPinned = null
        inputContainer?.toolbarView?.clearInlineAutofill()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute
        val policy = EditorPolicy.fromEditorInfo(attribute)
        val settings = preferences.read()
        controller.applySettings(settings, activeTextBridge())
        controller.onStartInput(policy)
        currentPage = if (policy.isNumericField) KeyboardPage.NUMPAD else KeyboardPage.ALPHA
        shiftState = ShiftState.OFF
        lastSpaceTapMs = 0L
        currentSuggestions = emptyList()
        hasSelection = false
        inputContainer?.setAuxPanel(ActiveAuxPanel.NONE)
        clearAutofillSuggestions()
        updateAutoCap()
        renderSoftUi()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        controller.setActiveSourceIfEmpty(InputSource.SOFT)
        syncSystemSubtype(controller.currentSettings().vnEnabled)
        inputContainer?.toolbarView?.setVoiceAvailable(findSystemVoiceImeId() != null)
        inputContainer?.refreshTheme(controller.currentSettings().themeMode)
        updateAutoCap()
        renderSoftUi()
        renderAuxPanels()
        predictions.resetLearnSignature()
        schedulePredictions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        predictions.save()
        clearAutofillSuggestions()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        currentEditorInfo = null
        clearAutofillSuggestions()
        controller.onFinishInput()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        controller.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
            activeTextBridge(),
        )
        hasSelection = newSelStart != newSelEnd
        if (lastSnapshot.empty) {
            updateAutoCap()
            renderSoftUi()
        }
        schedulePredictions()
    }

    // ---- Hardware keyboard ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isSystem) return super.onKeyDown(keyCode, event)
        typedSinceRefresh = true
        val hw = toHwKeyEvent(keyCode, event)
        if (controller.onHardwareKeyDown(hw, activeTextBridge())) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isSystem) return super.onKeyUp(keyCode, event)
        val hw = toHwKeyEvent(keyCode, event)
        if (controller.onHardwareKeyUp(hw, activeTextBridge())) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---- SessionCallbacks ----

    override fun onSnapshotChanged(snapshot: EngineSnapshot) {
        lastSnapshot = snapshot
        renderSoftUi()
    }

    override fun onVnEnabledToggled(enabled: Boolean, method: InputMethodMode) {
        preferences.setVnEnabled(enabled)
        syncSystemSubtype(enabled)
        schedulePredictions()
        val label = if (enabled) "vgo: ${method.shortLabel}" else "vgo: English"
        statusToast?.cancel()
        statusToast = Toast.makeText(this, label, Toast.LENGTH_SHORT).also { it.show() }
        renderSoftUi()
        renderAuxPanels()
    }

    // ---- System subtypes (Tiếng Việt / English) ----
    // The nav-bar globe and the system IME switcher switch subtypes; vgo maps them to vnEnabled.

    private fun subtypeIsVietnamese(subtype: InputMethodSubtype?): Boolean? =
        when (subtype?.getExtraValueOf("vgo_lang")) {
            "vi" -> true
            "en" -> false
            else -> null
        }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val wantVn = subtypeIsVietnamese(newSubtype) ?: return
        if (wantVn != controller.currentSettings().vnEnabled) {
            controller.toggleVnEnabled(activeTextBridge())
        }
    }

    /** Make the system's current subtype match vgo's Vietnamese on/off state. */
    private fun syncSystemSubtype(vnEnabled: Boolean) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (subtypeIsVietnamese(imm.currentInputMethodSubtype) == vnEnabled) return
        val myId = ComponentName(this, VgoInputMethodService::class.java).flattenToShortString()
        val info = imm.enabledInputMethodList.firstOrNull { it.id == myId } ?: return
        val target = imm.getEnabledInputMethodSubtypeList(info, true)
            .firstOrNull { subtypeIsVietnamese(it) == vnEnabled } ?: return
        switchInputMethod(myId, target)
    }

    // ---- VgoKeyboardView.KeyListener ----

    override fun onKeyAction(action: KeyAction) {
        typedSinceRefresh = true
        val bridge = activeTextBridge()
        when (action) {
            is KeyAction.Text -> {
                lastSpaceTapMs = 0L
                controller.onSoftText(action.value, bridge, isNumpad = action.isNumpad)
                if (shiftState == ShiftState.ONCE && action.value.any { it.isLetter() }) {
                    shiftState = ShiftState.OFF
                    renderSoftUi()
                }
            }
            KeyAction.Space -> handleSoftSpace(bridge)
            KeyAction.Backspace -> {
                lastSpaceTapMs = 0L
                controller.onSoftBackspace(bridge)
                if (lastSnapshot.empty) {
                    updateAutoCap()
                    renderSoftUi()
                }
            }
            KeyAction.Enter -> {
                lastSpaceTapMs = 0L
                controller.onSoftEnter(bridge)
                updateAutoCap()
                renderSoftUi()
            }
            KeyAction.Tab -> {
                lastSpaceTapMs = 0L
                controller.onSoftTab(bridge)
            }
            KeyAction.CapsLock -> {
                shiftState = if (shiftState == ShiftState.CAPS_LOCK) ShiftState.OFF else ShiftState.CAPS_LOCK
                renderSoftUi()
            }
            KeyAction.Shift -> {
                val now = SystemClock.uptimeMillis()
                shiftState = when (shiftState) {
                    ShiftState.OFF -> ShiftState.ONCE
                    ShiftState.ONCE -> if (now - lastShiftTapMs < 380L) ShiftState.CAPS_LOCK else ShiftState.OFF
                    ShiftState.CAPS_LOCK -> ShiftState.OFF
                }
                lastShiftTapMs = now
                renderSoftUi()
            }
            KeyAction.SwitchToSymbols1 -> {
                currentPage = KeyboardPage.SYMBOLS_1
                renderSoftUi()
            }
            KeyAction.SwitchToSymbols2 -> {
                currentPage = KeyboardPage.SYMBOLS_2
                renderSoftUi()
            }
            KeyAction.SwitchToAlpha -> {
                currentPage = KeyboardPage.ALPHA
                updateAutoCap()
                renderSoftUi()
            }
            KeyAction.CursorLeft -> moveCursor(-1)
            KeyAction.CursorRight -> moveCursor(1)
            KeyAction.ToggleEmoji -> toggleAuxPanel(ActiveAuxPanel.EMOJI)
            KeyAction.HideKeyboard -> {
                controller.finalizeComposing(bridge, 0)
                requestHideSelf(0)
            }
            KeyAction.ToggleVn -> controller.toggleVnEnabled(bridge)
            KeyAction.None -> Unit
        }
    }

    override fun onLongPressText(text: String) {
        typedSinceRefresh = true
        lastSpaceTapMs = 0L
        controller.onSoftText(text, activeTextBridge())
        if (shiftState == ShiftState.ONCE && text.any { it.isLetter() }) {
            shiftState = ShiftState.OFF
            renderSoftUi()
        }
    }

    override fun onSpaceSwipeCursor(steps: Int) {
        moveCursor(steps)
    }

    override fun onSpaceLongPress() {
        controller.finalizeComposing(activeTextBridge(), 0)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showInputMethodPicker()
    }

    override fun onBackspaceSwipeDeleteWord() {
        lastSpaceTapMs = 0L
        controller.onSoftDeleteWord(activeTextBridge())
        updateAutoCap()
        renderSoftUi()
    }

    override fun onShiftLongPress() {
        shiftState = if (shiftState == ShiftState.CAPS_LOCK) ShiftState.OFF else ShiftState.CAPS_LOCK
        renderSoftUi()
    }

    // ---- VgoToolbarView.Callbacks ----

    override fun onToggleMenuClicked() {
        val cur = inputContainer?.currentAuxPanel() ?: ActiveAuxPanel.NONE
        val target = if (cur == ActiveAuxPanel.NONE) ActiveAuxPanel.MENU else ActiveAuxPanel.NONE
        toggleAuxPanel(target)
    }

    override fun onFlipClicked() {
        controller.onSoftFlip(activeTextBridge())
    }

    override fun onCommitCurrentClicked() {
        controller.finalizeComposing(activeTextBridge(), 0)
    }

    override fun onPasteClipEntryClicked(entry: ClipboardEntry) {
        pasteClipboardEntry(entry)
        clipboardHistory.dismissSuggestion(entry.id)
        renderSoftUi()
    }

    override fun onSuggestionClicked(word: String) {
        typedSinceRefresh = true
        lastSpaceTapMs = 0L
        controller.applySuggestion(activeTextBridge(), word)
        updateAutoCap()
        renderSoftUi()
    }

    override fun onVoiceInputClicked() {
        controller.finalizeComposing(activeTextBridge(), 0)
        val voiceId = findSystemVoiceImeId() ?: return
        runCatching { switchInputMethod(voiceId) }
    }

    // ---- VgoMenuPanel.Callbacks ----

    override fun onOpenClipboard() {
        toggleAuxPanel(ActiveAuxPanel.CLIPBOARD)
    }

    override fun onOpenTextEdit() {
        toggleAuxPanel(ActiveAuxPanel.TEXT_EDIT)
    }

    override fun onSwitchMethod() {
        val source = controller.activeSource()
        val cur = controller.currentSettings().methodFor(source)
        val next = if (cur == InputMethodMode.VNI) InputMethodMode.TELEX else InputMethodMode.VNI
        preferences.setMethodFor(source, next)
        controller.applySettings(preferences.read(), activeTextBridge())
        renderAuxPanels()
        renderSoftUi()
    }

    override fun onToggleVn() {
        controller.toggleVnEnabled(activeTextBridge())
    }

    override fun onToggleSplit() {
        preferences.setSplitLayout(!controller.currentSettings().splitLayout)
        controller.applySettings(preferences.read(), activeTextBridge())
        inputContainer?.setAuxPanel(ActiveAuxPanel.NONE)
        renderSoftUi()
    }

    override fun onCycleTheme() {
        val next = controller.currentSettings().themeMode.next()
        preferences.setThemeMode(next)
        controller.applySettings(preferences.read(), activeTextBridge())
        inputContainer?.refreshTheme(next)
        renderAuxPanels()
        renderSoftUi()
    }

    override fun onToggleNoUnderline() {
        preferences.setNoUnderline(!controller.currentSettings().noUnderline)
        controller.applySettings(preferences.read(), activeTextBridge())
        renderAuxPanels()
        renderSoftUi()
    }

    override fun onOpenSettings() {
        inputContainer?.setAuxPanel(ActiveAuxPanel.NONE)
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onCloseMenu() {
        inputContainer?.setAuxPanel(ActiveAuxPanel.NONE)
        renderSoftUi()
    }

    // ---- VgoTextEditPanel.Callbacks ----

    override fun onMoveCursor(direction: VgoTextEditPanel.Direction, selecting: Boolean) {
        val kc = when (direction) {
            VgoTextEditPanel.Direction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            VgoTextEditPanel.Direction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            VgoTextEditPanel.Direction.UP -> KeyEvent.KEYCODE_DPAD_UP
            VgoTextEditPanel.Direction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        sendEditKey(kc, selecting)
    }

    override fun onSelectAll() {
        controller.finalizeComposing(activeTextBridge(), 0)
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    override fun onCut() {
        controller.finalizeComposing(activeTextBridge(), 0)
        currentInputConnection?.performContextMenuAction(android.R.id.cut)
    }

    override fun onCopy() {
        controller.finalizeComposing(activeTextBridge(), 0)
        currentInputConnection?.performContextMenuAction(android.R.id.copy)
    }

    override fun onPaste() {
        controller.finalizeComposing(activeTextBridge(), 0)
        currentInputConnection?.performContextMenuAction(android.R.id.paste)
    }

    override fun onMoveHome(selecting: Boolean) {
        sendEditKey(KeyEvent.KEYCODE_MOVE_HOME, selecting)
    }

    override fun onMoveEnd(selecting: Boolean) {
        sendEditKey(KeyEvent.KEYCODE_MOVE_END, selecting)
    }

    override fun onBackspace() {
        controller.onSoftBackspace(activeTextBridge())
    }

    override fun onClose() {
        inputContainer?.setAuxPanel(ActiveAuxPanel.NONE)
        renderSoftUi()
    }

    // ---- Helpers ----

    private fun pasteClipboardEntry(entry: ClipboardEntry) {
        val bridge = activeTextBridge() ?: return
        if (!entry.text.isNullOrEmpty()) {
            controller.onSoftText(entry.text, bridge)
            return
        }
        val uriStr = entry.imageUri ?: return
        val mime = entry.imageMime ?: "image/*"
        val ic = currentInputConnection ?: return
        val info = currentEditorInfo ?: return

        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        val supports = accepted.any { pattern ->
            pattern == "*/*" || pattern == "image/*" || pattern.equals(mime, ignoreCase = true)
        }
        if (!supports) {
            statusToast?.cancel()
            statusToast = Toast.makeText(this, "Ô nhập này không hỗ trợ dán ảnh", Toast.LENGTH_SHORT).also { it.show() }
            return
        }

        val contentInfo = InputContentInfoCompat(
            Uri.parse(uriStr),
            ClipDescription("vgo clipboard image", arrayOf(mime)),
            null,
        )
        controller.finalizeComposing(bridge, 0)
        val ok = runCatching {
            InputConnectionCompat.commitContent(
                ic,
                info,
                contentInfo,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null,
            )
        }.getOrDefault(false)
        if (!ok) {
            statusToast?.cancel()
            statusToast = Toast.makeText(this, "Ứng dụng này không nhận ảnh dán từ bàn phím", Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    private fun findSystemVoiceImeId(): String? {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return null
        val enabled = imm.enabledInputMethodList
        return enabled.firstOrNull { imi ->
            imi.id.contains("voice", ignoreCase = true) ||
                (0 until imi.subtypeCount).any { imi.getSubtypeAt(it).mode.equals("voice", ignoreCase = true) }
        }?.id
    }

    private fun sendEditKey(keyCode: Int, shift: Boolean) {
        controller.finalizeComposing(activeTextBridge(), 0)
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun toggleAuxPanel(target: ActiveAuxPanel) {
        val container = inputContainer ?: return
        val next = if (container.currentAuxPanel() == target) ActiveAuxPanel.NONE else target
        if (next != ActiveAuxPanel.NONE) {
            controller.finalizeComposing(activeTextBridge(), 0)
        }
        container.setAuxPanel(next)
        renderSoftUi()
        renderAuxPanels()
    }

    private fun handleSoftSpace(bridge: TextBridge?) {
        val now = SystemClock.uptimeMillis()
        val settings = controller.currentSettings()
        val policy = controller.currentPolicy()
        if (settings.doubleSpacePeriod &&
            !policy.bypassVietnamese &&
            bridge != null &&
            lastSnapshot.empty &&
            now - lastSpaceTapMs in 1..300L
        ) {
            val before = bridge.getTextBeforeCursor(2)
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                bridge.beginBatchEdit()
                try {
                    bridge.deleteSurroundingText(1, 0)
                    bridge.commitText(". ", 1)
                } finally {
                    bridge.endBatchEdit()
                }
                lastSpaceTapMs = 0L
                updateAutoCap()
                renderSoftUi()
                return
            }
        }
        controller.onSoftText(" ", bridge)
        lastSpaceTapMs = now
        updateAutoCap()
        renderSoftUi()
    }

    private fun moveCursor(steps: Int) {
        val bridge = activeTextBridge() ?: return
        controller.finalizeComposing(bridge, 0)
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) {
            bridge.sendDownUpKeyEvent(keyCode)
        }
    }

    private fun updateAutoCap() {
        if (currentPage != KeyboardPage.ALPHA || shiftState == ShiftState.CAPS_LOCK) return
        val settings = controller.currentSettings()
        val policy = controller.currentPolicy()
        if (!settings.autoCap || policy.bypassVietnamese) {
            shiftState = ShiftState.OFF
            return
        }
        if (policy.autoCapAll) {
            shiftState = ShiftState.CAPS_LOCK
            return
        }
        if (!policy.autoCapSentences && !policy.autoCapWords) return
        val info = currentEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = ic.getCursorCapsMode(info.inputType)
        shiftState = if (caps != 0) ShiftState.ONCE else ShiftState.OFF
    }

    private fun scheduleClipExpiryRefresh() {
        mainHandler.removeCallbacks(clipChipExpiryRunnable)
        val suggestion = clipboardHistory.activeSuggestion(controller.currentPolicy().isPassword) ?: return
        val remaining = (suggestion.timestampMs + LocalClipboardHistory.CHIP_SUGGESTION_TTL_MS) -
            System.currentTimeMillis() + 500L
        if (remaining > 0) {
            mainHandler.postDelayed(clipChipExpiryRunnable, remaining)
        }
    }

    private fun schedulePredictions() {
        mainHandler.removeCallbacks(predictRunnable)
        mainHandler.post(predictRunnable)
    }

    /** Reads the text before the cursor, learns the word just finished, and updates the suggestion strip. */
    private fun refreshPredictions() {
        val typed = typedSinceRefresh
        typedSinceRefresh = false
        val policy = controller.currentPolicy()
        val ic = currentInputConnection
        var next: List<String> = emptyList()
        var partialEmpty = true
        val settings = controller.currentSettings()
        if (ic != null && settings.suggestionsEnabled && !policy.noSuggestions && !policy.isPassword && !hasSelection) {
            val before = ic.getTextBeforeCursor(200, 0)?.toString()
            if (before != null) {
                val lang = if (settings.vnEnabled) "vi" else "en"
                if (typed && !policy.noLearning) predictions.learnFinishedWord(lang, before)
                if (isInputViewShown) {
                    val ctx = PredictContext.parse(before)
                    partialEmpty = ctx.partial.isEmpty()
                    next = predictions.suggest(lang, ctx, suggestionSlotCount())
                }
            }
        }
        if (next != currentSuggestions || partialEmpty != lastPartialEmpty) {
            currentSuggestions = next
            lastPartialEmpty = partialEmpty
            renderSoftUi()
        }
    }

    /** 3 like Gboard, or as many ~140dp slots as the strip width allows (3..7) when auto is on. */
    private fun suggestionSlotCount(): Int {
        if (!controller.currentSettings().autoSuggestionCount) return 3
        val toolbar = inputContainer?.toolbarView ?: return 3
        return toolbar.fittingSuggestionSlots()
    }

    private fun renderSoftUi() {
        val container = inputContainer ?: return
        val policy = controller.currentPolicy()
        val clipSuggestion = clipboardHistory.activeSuggestion(policy.isPassword)
        // Like Gboard: a fresh clipboard chip wins until the user starts typing a word.
        val suggestions = if (clipSuggestion != null && lastPartialEmpty) emptyList() else currentSuggestions
        container.render(
            page = currentPage,
            shiftState = shiftState,
            settings = controller.currentSettings(),
            activeSource = controller.activeSource(),
            policy = policy,
            snapshot = lastSnapshot,
            clipSuggestion = clipSuggestion,
            suggestions = suggestions,
            suggestionSlots = suggestionSlotCount(),
        )
    }

    private fun renderAuxPanels() {
        val palette = inputContainer?.currentPalette() ?: return
        val settings = controller.currentSettings()
        menuPanel?.render(settings, controller.activeSource(), palette)
        emojiPanel?.render(palette)
        textEditPanel?.render(palette)
        renderClipboardPanel()
    }

    private fun renderClipboardPanel() {
        val palette = inputContainer?.currentPalette() ?: return
        clipboardPanel?.render(clipboardHistory.items(), clipboardHistory.isEnabled(), palette)
    }

    private fun toHwKeyEvent(keyCode: Int, event: KeyEvent): HwKeyEvent {
        val isNumpad = keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
        val uni = event.getUnicodeChar(event.metaState)
        return HwKeyEvent(
            keyCode = keyCode,
            unicodeChar = uni,
            ctrlPressed = event.isCtrlPressed,
            altPressed = event.isAltPressed,
            metaPressed = event.isMetaPressed,
            shiftPressed = event.isShiftPressed,
            isNumpad = isNumpad,
            repeatCount = event.repeatCount,
        )
    }

    private fun activeTextBridge(): TextBridge? {
        val ic = currentInputConnection ?: return null
        return InputConnectionTextBridge(ic)
    }

    private class InputConnectionTextBridge(private val ic: InputConnection) : TextBridge {
        override fun beginBatchEdit() {
            ic.beginBatchEdit()
        }

        override fun endBatchEdit() {
            ic.endBatchEdit()
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
            ic.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText() {
            ic.finishComposingText()
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int) {
            ic.commitText(text, newCursorPosition)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            ic.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun getTextBeforeCursor(n: Int): String =
            ic.getTextBeforeCursor(n, 0)?.toString().orEmpty()

        override fun sendDownUpKeyEvent(keyCode: Int) {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }

        override fun performEditorAction(imeAction: Int): Boolean =
            ic.performEditorAction(imeAction)
    }
}
