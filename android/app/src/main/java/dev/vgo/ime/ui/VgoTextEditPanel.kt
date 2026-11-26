package dev.vgo.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Gboard-style Text Editing pad opened from the Grid menu:
 * D-pad arrows (▲ ▼ ◀ ▶), Select toggle, Select All, Cut, Copy, Paste, Home, End, Backspace.
 */
class VgoTextEditPanel(
    context: Context,
    private val callbacks: Callbacks,
) : LinearLayout(context) {

    interface Callbacks {
        fun onMoveCursor(direction: Direction, selecting: Boolean)
        fun onSelectAll()
        fun onCut()
        fun onCopy()
        fun onPaste()
        fun onMoveHome(selecting: Boolean)
        fun onMoveEnd(selecting: Boolean)
        fun onBackspace()
        fun onClose()
    }

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    private val density = resources.displayMetrics.density
    private val gboardTypeface = VgoKeyboardView.loadSystemGoogleSans()
    private var palette = KeyboardTheme.resolve(context)
    private var selecting = false

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
        setOnClickListener {
            selecting = false
            callbacks.onClose()
        }
    }
    private val titleView = TextView(context).apply {
        text = "Chỉnh sửa văn bản"
        typeface = gboardTypeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), 0, 0, 0)
    }

    private val grid = GridLayout(context).apply {
        setPadding(dp(14), dp(6), dp(14), dp(12))
    }

    init {
        orientation = VERTICAL
        header.addView(backBtn)
        header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, dp(245)))
    }

    fun render(newPalette: KeyboardPalette) {
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

        val buttons = listOf(
            Btn("⇤ Đầu dòng", false) { callbacks.onMoveHome(selecting) },
            Btn("▲", false) { callbacks.onMoveCursor(Direction.UP, selecting) },
            Btn("Cuối dòng ⇥", false) { callbacks.onMoveEnd(selecting) },
            Btn("Chọn hết", false) { callbacks.onSelectAll() },

            Btn("◀", false) { callbacks.onMoveCursor(Direction.LEFT, selecting) },
            Btn(if (selecting) "Đang chọn" else "Chọn", selecting) {
                selecting = !selecting
                render(palette)
            },
            Btn("▶", false) { callbacks.onMoveCursor(Direction.RIGHT, selecting) },
            Btn("Cắt", false) { callbacks.onCut(); selecting = false; render(palette) },

            Btn("Sao chép", false) { callbacks.onCopy(); selecting = false; render(palette) },
            Btn("▼", false) { callbacks.onMoveCursor(Direction.DOWN, selecting) },
            Btn("Dán", false) { callbacks.onPaste(); selecting = false; render(palette) },
            Btn("⌫", false) { callbacks.onBackspace() },
        )

        buttons.forEachIndexed { idx, btn ->
            val tv = TextView(context).apply {
                text = btn.label
                typeface = gboardTypeface
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (btn.active) palette.chipActiveText else palette.keyText)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (btn.active) palette.chipActiveBg else palette.keyBg)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { btn.onClick() }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(idx / 4, 1f),
                GridLayout.spec(idx % 4, 1f),
            ).apply {
                width = 0
                height = 0
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(tv, lp)
        }
    }

    private data class Btn(val label: String, val active: Boolean, val onClick: () -> Unit)

    private fun dp(v: Int): Int = (v * density).toInt()
}
