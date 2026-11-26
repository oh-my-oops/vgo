package dev.vgo.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.vgo.ime.engine.InputMethodMode
import dev.vgo.ime.prefs.InputSource
import dev.vgo.ime.prefs.VgoSettings

/**
 * Gboard-style 2x4 icon tile tray opened from the top-left 4-square grid icon:
 * Clipboard, Text Editing, VNI/Telex, VN/EN, Split, Theme (System/Dark/Light), No-underline, Settings.
 */
class VgoMenuPanel(
    context: Context,
    private val callbacks: Callbacks,
) : LinearLayout(context) {

    interface Callbacks {
        fun onOpenClipboard()
        fun onOpenTextEdit()
        fun onSwitchMethod()
        fun onToggleVn()
        fun onToggleSplit()
        fun onCycleTheme()
        fun onToggleNoUnderline()
        fun onOpenSettings()
        fun onCloseMenu()
    }

    private val density = resources.displayMetrics.density
    private val gboardTypeface = VgoKeyboardView.loadSystemGoogleSans()
    private var palette = KeyboardTheme.resolve(context)

    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }
    private val backBtn = TextView(context).apply {
        text = "ABC"
        typeface = gboardTypeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isClickable = true
        isFocusable = true
        setOnClickListener { callbacks.onCloseMenu() }
    }
    private val titleView = TextView(context).apply {
        text = "Công cụ vgo"
        typeface = gboardTypeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), 0, 0, 0)
    }

    private val grid = GridLayout(context).apply {
        setPadding(dp(12), dp(6), dp(12), dp(12))
    }

    init {
        orientation = VERTICAL
        header.addView(backBtn)
        header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, dp(245)))
    }

    fun render(settings: VgoSettings, activeSource: InputSource, newPalette: KeyboardPalette) {
        palette = newPalette
        setBackgroundColor(palette.background)
        titleView.setTextColor(palette.keyText)
        backBtn.setTextColor(palette.keyText)
        backBtn.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(palette.funcKeyBg)
        }

        val panelHeightDp = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            215
        } else {
            255
        }
        grid.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(panelHeightDp))
        grid.removeAllViews()
        grid.columnCount = 4

        val curMethod = settings.methodFor(activeSource)
        val nextMethod = if (curMethod == InputMethodMode.VNI) "Telex" else "VNI"
        val tiles = listOf(
            Tile("⎘", "Clipboard", if (settings.clipboardEnabled) "Đang bật" else "Đang tắt", false) {
                callbacks.onOpenClipboard()
            },
            Tile("⊞", "Sửa văn bản", "Con trỏ & chọn", false) {
                callbacks.onOpenTextEdit()
            },
            Tile(curMethod.shortLabel, "Kiểu gõ", "${activeSource.label} → $nextMethod", true) {
                callbacks.onSwitchMethod()
            },
            Tile(if (settings.vnEnabled) "VI" else "EN", if (settings.vnEnabled) "Tiếng Việt" else "English", "Chuyển ngôn ngữ", settings.vnEnabled) {
                callbacks.onToggleVn()
            },
            Tile("◫", "Tách đôi", if (settings.splitLayout) "Đang tách" else "Đầy đủ", settings.splitLayout) {
                callbacks.onToggleSplit()
            },
            Tile("◐", "Chủ đề", settings.themeMode.label, false) {
                callbacks.onCycleTheme()
            },
            Tile("A", "Không gạch", if (settings.noUnderline) "Đang bật" else "Gạch chân", settings.noUnderline) {
                callbacks.onToggleNoUnderline()
            },
            Tile("⚙\uFE0E", "Cài đặt", "Tùy chỉnh & học", false) {
                callbacks.onOpenSettings()
            },
        )

        tiles.forEachIndexed { idx, tile ->
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (tile.active) palette.chipActiveBg else palette.keyBg)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { tile.onClick() }
            }
            val iconTv = TextView(context).apply {
                text = tile.glyph
                typeface = gboardTypeface
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTextColor(if (tile.active) palette.chipActiveText else palette.keyText)
            }
            val titleTv = TextView(context).apply {
                text = tile.title
                typeface = gboardTypeface
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (tile.active) palette.chipActiveText else palette.keyText)
                setPadding(0, dp(4), 0, 0)
            }
            val subTv = TextView(context).apply {
                text = tile.subtitle
                typeface = gboardTypeface
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (tile.active) palette.chipActiveText else palette.keyHintText)
            }
            card.addView(iconTv)
            card.addView(titleTv)
            card.addView(subTv)

            val lp = GridLayout.LayoutParams(
                GridLayout.spec(idx / 4, 1f),
                GridLayout.spec(idx % 4, 1f),
            ).apply {
                width = 0
                height = 0
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(card, lp)
        }
    }

    private data class Tile(
        val glyph: String,
        val title: String,
        val subtitle: String,
        val active: Boolean,
        val onClick: () -> Unit,
    )

    private fun dp(v: Int): Int = (v * density).toInt()
}
