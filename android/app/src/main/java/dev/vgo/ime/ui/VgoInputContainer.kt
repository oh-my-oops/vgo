package dev.vgo.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.vgo.ime.clipboard.ClipboardEntry
import dev.vgo.ime.engine.EngineSnapshot
import dev.vgo.ime.ime.EditorPolicy
import dev.vgo.ime.prefs.InputSource
import dev.vgo.ime.prefs.ThemeMode
import dev.vgo.ime.prefs.VgoSettings

enum class ActiveAuxPanel {
    NONE,
    MENU,
    EMOJI,
    CLIPBOARD,
    TEXT_EDIT,
}

/**
 * Root view for the IME soft keyboard: Gboard-style top bar + 5-row keyboard (or Menu/Emoji/Clipboard/TextEdit).
 * Keeps clipChildren = false so Row 0's floating key preview bubble renders seamlessly above the keys.
 */
class VgoInputContainer(
    context: Context,
    toolbarCallbacks: VgoToolbarView.Callbacks,
    keyListener: VgoKeyboardView.KeyListener,
) : LinearLayout(context) {

    val toolbarView = VgoToolbarView(context, toolbarCallbacks)
    val keyboardView = VgoKeyboardView(context, keyListener)
    private val auxHost = FrameLayout(context).apply { visibility = GONE }

    private var palette = KeyboardTheme.resolve(context)
    private var activeAuxPanel = ActiveAuxPanel.NONE
    private var menuPanelView: View? = null
    private var emojiPanelView: View? = null
    private var clipboardPanelView: View? = null
    private var textEditPanelView: View? = null

    private var lastRows: List<List<KeySpec>>? = null
    private var lastPalette: KeyboardPalette? = null
    private var lastHeightPct: Int = -1
    private var lastHaptic: Boolean? = null
    private var lastSwipeCursor: Boolean? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        val toolbarHeightPx = (56f * resources.displayMetrics.density).toInt()
        addView(toolbarView, LayoutParams(LayoutParams.MATCH_PARENT, toolbarHeightPx))
        addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(auxHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setBackgroundColor(palette.background)

        // Android 15+ edge-to-edge IME: pad below keyboard rows for system nav bar / gesture pill.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
    }

    fun setAuxPanels(menuView: View?, emojiView: View?, clipboardView: View?, textEditView: View?) {
        menuPanelView = menuView
        emojiPanelView = emojiView
        clipboardPanelView = clipboardView
        textEditPanelView = textEditView
        auxHost.removeAllViews()
        listOfNotNull(menuView, emojiView, clipboardView, textEditView).forEach { v ->
            v.visibility = GONE
            auxHost.addView(v, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    fun currentAuxPanel(): ActiveAuxPanel = activeAuxPanel

    fun setAuxPanel(panel: ActiveAuxPanel) {
        activeAuxPanel = panel
        menuPanelView?.visibility = if (panel == ActiveAuxPanel.MENU) VISIBLE else GONE
        emojiPanelView?.visibility = if (panel == ActiveAuxPanel.EMOJI) VISIBLE else GONE
        clipboardPanelView?.visibility = if (panel == ActiveAuxPanel.CLIPBOARD) VISIBLE else GONE
        textEditPanelView?.visibility = if (panel == ActiveAuxPanel.TEXT_EDIT) VISIBLE else GONE

        val showingAux = panel != ActiveAuxPanel.NONE
        auxHost.visibility = if (showingAux) VISIBLE else GONE
        keyboardView.visibility = if (showingAux) GONE else VISIBLE
    }

    fun refreshTheme(themeMode: ThemeMode) {
        palette = KeyboardTheme.resolve(context, themeMode)
        setBackgroundColor(palette.background)
        toolbarView.applyTheme(palette)
    }

    fun currentPalette(): KeyboardPalette = palette

    fun render(
        page: KeyboardPage,
        shiftState: ShiftState,
        settings: VgoSettings,
        activeSource: InputSource,
        policy: EditorPolicy,
        snapshot: EngineSnapshot,
        clipSuggestion: ClipboardEntry?,
        suggestions: List<String> = emptyList(),
        suggestionSlots: Int = 3,
    ) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val heightPct = if (isLandscape) settings.landscapeHeightPct else settings.portraitHeightPct
        val rows = KeyboardLayoutBuilder.buildRows(
            page = page,
            shiftState = shiftState,
            mode = settings.methodFor(activeSource),
            vnEnabled = settings.vnEnabled && !policy.bypassVietnamese,
            showNumberRow = settings.showNumberRow,
            showVniHints = settings.showVniHints,
            split = settings.splitLayout,
            isLandscape = isLandscape,
            imeAction = policy.imeAction,
            isMultiline = policy.isMultiline,
        )
        if (rows != lastRows ||
            palette != lastPalette ||
            heightPct != lastHeightPct ||
            settings.hapticEnabled != lastHaptic ||
            settings.swipeSpaceCursor != lastSwipeCursor
        ) {
            lastRows = rows
            lastPalette = palette
            lastHeightPct = heightPct
            lastHaptic = settings.hapticEnabled
            lastSwipeCursor = settings.swipeSpaceCursor
            keyboardView.updateState(
                newRows = rows,
                newPalette = palette,
                newHeightPercent = heightPct,
                newHapticEnabled = settings.hapticEnabled,
                newSwipeSpaceCursor = settings.swipeSpaceCursor,
            )
        }
        toolbarView.render(
            snapshot = snapshot,
            vnEnabled = settings.vnEnabled && !policy.bypassVietnamese,
            activePanel = activeAuxPanel,
            clipSuggestion = clipSuggestion,
            suggestions = suggestions,
            suggestionSlots = suggestionSlots,
        )
    }
}
