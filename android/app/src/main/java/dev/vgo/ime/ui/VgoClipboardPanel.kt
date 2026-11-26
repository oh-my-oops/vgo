package dev.vgo.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.vgo.ime.clipboard.ClipboardEntry

/**
 * Gboard-style Clipboard panel:
 * - Header with ABC return, ON/OFF toggle switch, and Clear-unpinned button
 * - 2-3 column card grid with Pinned section at top, unpinned below (1h auto-expiry)
 * - Long-press (or ⋮ tap) on any card opens context menu: Ghim/Bỏ ghim, Dán, Xoá
 * - Supports image/screenshot thumbnail preview + commitContent paste
 */
class VgoClipboardPanel(
    context: Context,
    private val onPaste: (ClipboardEntry) -> Unit,
    private val onTogglePin: (Long) -> Unit,
    private val onDelete: (Long) -> Unit,
    private val onClearUnpinned: () -> Unit,
    private val onToggleEnabled: (Boolean) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val gboardTypeface = VgoKeyboardView.loadSystemGoogleSans()
    private var palette = KeyboardTheme.resolve(context)
    private var suppressSwitchCallback = false

    private val headerBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }
    private val backBtn = createHeaderButton("ABC") { onClose() }
    private val titleView = TextView(context).apply {
        text = "Bảng nhớ tạm"
        typeface = gboardTypeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), 0, dp(8), 0)
    }
    private val clearAllBtn = createHeaderButton("Xóa chưa ghim") { onClearUnpinned() }
    private val enableSwitch = Switch(context).apply {
        setOnCheckedChangeListener { _, isChecked ->
            if (!suppressSwitchCallback) onToggleEnabled(isChecked)
        }
    }

    private val scrollView = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
    private val contentColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(10))
    }
    private val emptyView = TextView(context).apply {
        typeface = gboardTypeface
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(dp(20), dp(36), dp(20), dp(36))
    }

    init {
        orientation = VERTICAL
        headerBar.addView(backBtn)
        headerBar.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerBar.addView(clearAllBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        headerBar.addView(enableSwitch)

        addView(headerBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scrollView.addView(contentColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, dp(245)))
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, dp(245)))
    }

    fun render(items: List<ClipboardEntry>, enabled: Boolean, newPalette: KeyboardPalette) {
        palette = newPalette
        setBackgroundColor(palette.background)
        titleView.setTextColor(palette.keyText)
        emptyView.setTextColor(palette.keyHintText)
        styleHeaderButton(backBtn)
        styleHeaderButton(clearAllBtn)

        suppressSwitchCallback = true
        enableSwitch.isChecked = enabled
        suppressSwitchCallback = false

        val panelHeightDp = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            215
        } else {
            255
        }
        scrollView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(panelHeightDp))
        emptyView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(panelHeightDp))

        val hasUnpinned = items.any { !it.pinned }
        clearAllBtn.visibility = if (enabled && hasUnpinned) VISIBLE else GONE

        if (!enabled) {
            scrollView.visibility = GONE
            emptyView.visibility = VISIBLE
            emptyView.text = "Bảng nhớ tạm đang tắt.\nBật công tắc phía trên để lưu mục sao chép (tự xóa sau 1 giờ, không lưu mật khẩu)."
            return
        }

        if (items.isEmpty()) {
            scrollView.visibility = GONE
            emptyView.visibility = VISIBLE
            emptyView.text = "Chưa có mục sao chép nào.\nMục chưa ghim tự xóa sau 1 giờ · Nhấn giữ thẻ để ghim."
            return
        }

        scrollView.visibility = VISIBLE
        emptyView.visibility = GONE
        contentColumn.removeAllViews()

        val pinned = items.filter { it.pinned }
        val unpinned = items.filter { !it.pinned }
        val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

        if (pinned.isNotEmpty()) {
            contentColumn.addView(createSectionHeader("Đã ghim"))
            contentColumn.addView(buildGrid(pinned, columns))
        }
        if (unpinned.isNotEmpty()) {
            contentColumn.addView(createSectionHeader("Gần đây (tự xóa sau 1 giờ)"))
            contentColumn.addView(buildGrid(unpinned, columns))
        }
    }

    private fun buildGrid(entries: List<ClipboardEntry>, columns: Int): GridLayout {
        val grid = GridLayout(context).apply { columnCount = columns }
        entries.forEachIndexed { idx, entry ->
            val card = buildCard(entry)
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(idx / columns),
                GridLayout.spec(idx % columns, 1f),
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(card, lp)
        }
        return grid
    }

    private fun buildCard(entry: ClipboardEntry): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (entry.pinned) palette.funcKeyBg else palette.keyBg)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onPaste(entry) }
            setOnLongClickListener { v ->
                showCardMenu(v, entry)
                true
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (entry.isImage) {
            val bmp = decodeThumb(entry.imageUri!!)
            if (bmp != null) {
                val img = ImageView(context).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                topRow.addView(img, LayoutParams(0, dp(64), 1f))
            } else {
                val tv = TextView(context).apply {
                    text = "🖼 ${entry.previewLabel()}"
                    typeface = gboardTypeface
                    setTextColor(palette.keyText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                }
                topRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        } else {
            val tv = TextView(context).apply {
                text = (if (entry.pinned) "📌 " else "") + entry.text.orEmpty()
                typeface = gboardTypeface
                setTextColor(palette.keyText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
            topRow.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        val moreBtn = TextView(context).apply {
            text = "⋮"
            setTextColor(palette.keyHintText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            minWidth = dp(28)
            minHeight = dp(28)
            isClickable = true
            isFocusable = true
            setOnClickListener { v -> showCardMenu(v, entry) }
        }
        topRow.addView(moreBtn)
        card.addView(topRow)
        return card
    }

    private fun showCardMenu(anchor: View, entry: ClipboardEntry) {
        val menu = PopupMenu(context, anchor)
        menu.menu.add(0, 1, 0, "Dán")
        menu.menu.add(0, 2, 1, if (entry.pinned) "Bỏ ghim" else "Ghim")
        menu.menu.add(0, 3, 2, "Xóa")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> onPaste(entry)
                2 -> onTogglePin(entry.id)
                3 -> onDelete(entry.id)
            }
            true
        }
        runCatching { menu.show() }.onFailure {
            // Fallback inside IME window if PopupMenu token is restricted: cycle pin cleanly.
            onTogglePin(entry.id)
        }
    }

    private fun decodeThumb(uriStr: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriStr)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > 240 || opts.outHeight / sample > 240) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
    }.getOrNull()

    private fun createSectionHeader(label: String): TextView = TextView(context).apply {
        text = label
        typeface = gboardTypeface
        setTextColor(palette.keyHintText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(6), dp(6), dp(6), dp(2))
    }

    private fun createHeaderButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            typeface = gboardTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun styleHeaderButton(view: TextView) {
        view.setTextColor(palette.keyText)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(palette.funcKeyBg)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
