package dev.vgo.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.vgo.ime.clipboard.ClipboardEntry
import dev.vgo.ime.engine.EngineSnapshot

/**
 * Pixel-calibrated Gboard top strip (56dp):
 * - Left (80dp slot): crisp 2x2 Material Symbols grid icon + hairline divider
 * - Center: priority order -> Inline Autofill strip (M6) > word suggestions (+ flip chip) > active word + flip chip
 *   > recent clipboard chip
 * - Right (80dp slot, hidden if system has no voice IME): hairline divider + circular 36dp mic button
 */
class VgoToolbarView(
    context: Context,
    private val callbacks: Callbacks,
) : LinearLayout(context) {

    interface Callbacks {
        fun onToggleMenuClicked()
        fun onFlipClicked()
        fun onCommitCurrentClicked()
        fun onPasteClipEntryClicked(entry: ClipboardEntry)
        fun onVoiceInputClicked()
        fun onSuggestionClicked(word: String)
    }

    private val density = resources.displayMetrics.density
    private val gboardTypeface = VgoKeyboardView.loadSystemGoogleSans()
    private var palette = KeyboardTheme.resolve(context)
    private var activeClipEntry: ClipboardEntry? = null
    private var hasInlineAutofill: Boolean = false
    private var loggedLayoutBounds: Boolean = false

    private val gridIconButton = GridIconView(context).apply {
        contentDescription = "Menu công cụ"
        isClickable = true
        isFocusable = true
        setOnClickListener { callbacks.onToggleMenuClicked() }
    }

    private val leftDivider = View(context)
    private val rightDivider = View(context)

    private val centerArea = FrameLayout(context)

    // M6 Inline Autofill host (horizontal scroll for suggestions + pinned icon at right)
    private val autofillContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val autofillScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }
    private val autofillStrip = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val autofillPinnedHost = FrameLayout(context).apply {
        visibility = GONE
    }

    // Active typing word + 1-tap Esc flip chip
    private val composingRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private val currentChip = createPill("") { callbacks.onCommitCurrentClicked() }
    private val flipChip = createPill("") { callbacks.onFlipClicked() }

    // Gboard-style suggestion strip: best word in the middle (bold), next ones alternate left/right.
    // Slot count is 3 (Gboard) or fitted to the strip width (see fittingSuggestionSlots).
    private val suggestionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val suggestionFlipChip = createPill("") { callbacks.onFlipClicked() }
    private val suggestionSlots = ArrayList<TextView>() // in visual (left-to-right) order
    private val suggestionDividers = ArrayList<View>()
    private var rankToSlot: IntArray = IntArray(0)
    private var shownSuggestions: List<String> = emptyList()
    private var slotCount = 0

    // Gboard-style centered clipboard suggestion pill (clipboard icon + ellipsized text)
    private val clipPillContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(0), dp(14), dp(0))
        isClickable = true
        isFocusable = true
        visibility = GONE
        setOnClickListener {
            activeClipEntry?.let { callbacks.onPasteClipEntryClicked(it) }
        }
    }
    private val clipIconView = ClipboardIconView(context)
    private val clipThumbView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        visibility = GONE
    }
    private var clipThumbUri: String? = null
    private val clipTextView = TextView(context).apply {
        typeface = gboardTypeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(290)
        setPadding(dp(8), 0, 0, 0)
    }

    private val micButton = MicCircleView(context).apply {
        contentDescription = "Nhập bằng giọng nói"
        isClickable = true
        isFocusable = true
        setOnClickListener { callbacks.onVoiceInputClicked() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(56))

        // Autofill strip setup
        autofillScroll.addView(
            autofillStrip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )
        autofillContainer.addView(autofillScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        autofillContainer.addView(
            autofillPinnedHost,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginStart = dp(6) }
        )

        composingRow.addView(
            currentChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        )
        composingRow.addView(flipChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        suggestionRow.addView(
            suggestionFlipChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        )
        setSlotCount(3)

        clipPillContainer.addView(clipIconView, LayoutParams(dp(18), dp(18)))
        clipPillContainer.addView(clipThumbView, LayoutParams(dp(40), dp(28)))
        clipPillContainer.addView(clipTextView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        centerArea.addView(
            autofillContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        centerArea.addView(
            suggestionRow,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        centerArea.addView(
            composingRow,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        centerArea.addView(
            clipPillContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(36), Gravity.CENTER)
        )

        // Exact Gboard geometry: grid centered in 80dp left slot, divider at x=80dp, divider at x=720dp, mic centered in 80dp right slot
        addView(gridIconButton, LayoutParams(dp(79), dp(40)))
        addView(leftDivider, LayoutParams(dp(1), dp(26)))
        addView(centerArea, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
        addView(rightDivider, LayoutParams(dp(1), dp(26)))
        addView(micButton, LayoutParams(dp(79), dp(40)))

        applyTheme(palette)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!loggedLayoutBounds && (r - l) > 0 && (b - t) > 0) {
            loggedLayoutBounds = true
            Log.i(
                "VgoToolbar",
                "layout toolbar=${r - l}x${b - t} vis=$visibility " +
                    "grid=${gridIconButton.width}x${gridIconButton.height}@(${gridIconButton.left},${gridIconButton.top}) " +
                    "center=${centerArea.width}x${centerArea.height} " +
                    "mic=${micButton.width}x${micButton.height} vis=${micButton.visibility}"
            )
        }
    }

    fun setVoiceAvailable(available: Boolean) {
        val vis = if (available) VISIBLE else GONE
        rightDivider.visibility = vis
        micButton.visibility = vis
    }

    fun setInlineAutofillViews(regularViews: List<View>, pinnedView: View?) {
        autofillStrip.removeAllViews()
        autofillPinnedHost.removeAllViews()

        regularViews.forEachIndexed { idx, v ->
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (idx > 0) marginStart = dp(6)
            }
            autofillStrip.addView(v, lp)
        }
        if (pinnedView != null) {
            autofillPinnedHost.addView(
                pinnedView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
            )
            autofillPinnedHost.visibility = VISIBLE
        } else {
            autofillPinnedHost.visibility = GONE
        }

        hasInlineAutofill = regularViews.isNotEmpty() || pinnedView != null
        updateCenterVisibility(snapshot = null, vnEnabled = false, activePanel = ActiveAuxPanel.NONE, clipSuggestion = activeClipEntry)
    }

    fun clearInlineAutofill() {
        if (!hasInlineAutofill) return
        autofillStrip.removeAllViews()
        autofillPinnedHost.removeAllViews()
        autofillPinnedHost.visibility = GONE
        hasInlineAutofill = false
    }

    fun applyTheme(newPalette: KeyboardPalette) {
        palette = newPalette
        setBackgroundColor(palette.toolbarBackground)
        leftDivider.setBackgroundColor(palette.divider)
        rightDivider.setBackgroundColor(palette.divider)
        gridIconButton.setColors(palette.keyText, palette.chipActiveBg)
        micButton.setColors(palette.chipBg, palette.keyText)
        clipIconView.setColor(palette.keyText)
        clipTextView.setTextColor(palette.keyText)
        clipPillContainer.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(palette.chipBg)
        }
        stylePill(currentChip, palette.chipBg, palette.keyText)
        stylePill(flipChip, palette.chipFlipBg, palette.chipFlipText)
        stylePill(suggestionFlipChip, palette.chipFlipBg, palette.chipFlipText)
        styleSuggestionSlots()
    }

    fun render(
        snapshot: EngineSnapshot,
        vnEnabled: Boolean,
        activePanel: ActiveAuxPanel,
        clipSuggestion: ClipboardEntry?,
        suggestions: List<String> = emptyList(),
        suggestionSlots: Int = 3,
    ) {
        setSlotCount(suggestionSlots)
        gridIconButton.setActive(activePanel != ActiveAuxPanel.NONE)
        activeClipEntry = clipSuggestion
        shownSuggestions = suggestions
        updateCenterVisibility(snapshot, vnEnabled, activePanel, clipSuggestion)
    }

    private fun updateCenterVisibility(
        snapshot: EngineSnapshot?,
        vnEnabled: Boolean,
        activePanel: ActiveAuxPanel,
        clipSuggestion: ClipboardEntry?,
    ) {
        // Priority 1: Inline Autofill (Bitwarden / Credential Manager) always wins over clipboard.
        if (hasInlineAutofill && activePanel == ActiveAuxPanel.NONE) {
            autofillContainer.visibility = VISIBLE
            suggestionRow.visibility = GONE
            composingRow.visibility = GONE
            clipPillContainer.visibility = GONE
            return
        }
        autofillContainer.visibility = GONE

        // Priority 2: word completions / next-word predictions (flip chip kept on the left).
        if (shownSuggestions.isNotEmpty() && activePanel == ActiveAuxPanel.NONE) {
            suggestionRow.visibility = VISIBLE
            composingRow.visibility = GONE
            clipPillContainer.visibility = GONE
            val flip = snapshot != null && !snapshot.empty && vnEnabled && snapshot.hasFlipCandidate
            suggestionFlipChip.visibility = if (flip) VISIBLE else GONE
            if (flip && snapshot != null) suggestionFlipChip.text = "↺ ${snapshot.altDisplay}"
            for (rank in rankToSlot.indices) {
                val tv = suggestionSlots[rankToSlot[rank]]
                val word = shownSuggestions.getOrNull(rank)
                tv.text = word.orEmpty()
                tv.tag = word
                tv.isClickable = word != null
            }
            // A divider shows only between two filled slots.
            suggestionDividers.forEachIndexed { i, d ->
                val filled = suggestionSlots[i].tag != null && suggestionSlots[i + 1].tag != null
                d.visibility = if (filled) VISIBLE else INVISIBLE
            }
            return
        }
        suggestionRow.visibility = GONE

        // Priority 3: Active composing word + Esc flip chip.
        if (snapshot != null && !snapshot.empty && vnEnabled) {
            composingRow.visibility = VISIBLE
            clipPillContainer.visibility = GONE
            currentChip.text = snapshot.display
            if (snapshot.hasFlipCandidate) {
                flipChip.visibility = VISIBLE
                flipChip.text = "↺ ${snapshot.altDisplay}"
            } else {
                flipChip.visibility = GONE
            }
            return
        }
        composingRow.visibility = GONE

        // Priority 4: Recent clipboard suggestion chip (like Gboard).
        if (clipSuggestion != null && activePanel == ActiveAuxPanel.NONE) {
            clipPillContainer.visibility = VISIBLE
            clipTextView.text = clipSuggestion.previewLabel()
            val thumb = if (clipSuggestion.isImage) loadClipThumb(clipSuggestion.imageUri!!) else null
            clipThumbView.visibility = if (thumb != null) VISIBLE else GONE
            clipIconView.visibility = if (thumb != null) GONE else VISIBLE
        } else {
            clipPillContainer.visibility = GONE
        }
    }

    private fun createPill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        typeface = gboardTypeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(14), dp(7), dp(14), dp(7))
        minHeight = dp(34)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Small decoded preview for the clipboard chip; cached for the last image only. */
    private fun loadClipThumb(uriStr: String): android.graphics.drawable.Drawable? {
        if (clipThumbUri == uriStr) return clipThumbView.drawable
        clipThumbUri = uriStr
        val bmp = runCatching {
            val uri = android.net.Uri.parse(uriStr)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= dp(40) && bounds.outHeight / (sample * 2) >= dp(28)) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
        clipThumbView.setImageBitmap(bmp)
        clipThumbView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
            }
        }
        return clipThumbView.drawable
    }

    /** How many ~[SLOT_MIN_DP] slots fit in the strip (3..[MAX_SLOTS]). */
    fun fittingSuggestionSlots(): Int {
        // From the current screen width (not the laid-out width, which is stale right after rotation).
        val micPx = if (micButton.visibility == VISIBLE) dp(80) else 0
        val stripPx = resources.displayMetrics.widthPixels - dp(80) - micPx - dp(18)
        return (stripPx / dp(SLOT_MIN_DP)).coerceIn(3, MAX_SLOTS)
    }

    private fun setSlotCount(n: Int) {
        val count = n.coerceIn(1, MAX_SLOTS)
        if (count == slotCount) return
        slotCount = count
        suggestionSlots.forEach { suggestionRow.removeView(it) }
        suggestionDividers.forEach { suggestionRow.removeView(it) }
        suggestionSlots.clear()
        suggestionDividers.clear()
        for (i in 0 until count) {
            if (i > 0) {
                val d = View(context)
                suggestionDividers.add(d)
                suggestionRow.addView(d, LayoutParams(dp(1), dp(20)))
            }
            val tv = createSuggestionSlot()
            suggestionSlots.add(tv)
            suggestionRow.addView(tv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        rankToSlot = rankOrder(count)
        styleSuggestionSlots()
    }

    private fun styleSuggestionSlots() {
        val bold = android.graphics.Typeface.create(gboardTypeface, android.graphics.Typeface.BOLD)
        suggestionSlots.forEachIndexed { i, tv ->
            tv.setTextColor(palette.keyText)
            tv.typeface = if (rankToSlot.isNotEmpty() && rankToSlot[0] == i) bold else gboardTypeface
        }
        suggestionDividers.forEach { it.setBackgroundColor(palette.divider) }
    }

    private fun createSuggestionSlot(): TextView = TextView(context).apply {
        typeface = gboardTypeface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(6), 0, dp(6), 0)
        isFocusable = true
        setOnClickListener { (tag as? String)?.let { callbacks.onSuggestionClicked(it) } }
    }

    private fun stylePill(view: TextView, bg: Int, fg: Int) {
        view.setTextColor(fg)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(bg)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        const val SLOT_MIN_DP = 140
        const val MAX_SLOTS = 7

        /** Visual slot for each rank: best in the middle, then left, right, left, ... */
        fun rankOrder(count: Int): IntArray {
            val center = (count - 1) / 2
            val out = IntArray(count)
            var left = center - 1
            var right = center + 1
            out[0] = center
            for (rank in 1 until count) {
                out[rank] = if ((rank % 2 == 1 && left >= 0) || right >= count) left-- else right++
            }
            return out
        }
    }

    /** Material Symbols 2x2 rounded grid icon centered in Gboard's 80dp left slot. */
    private class GridIconView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()
        private var active = false

        fun setColors(fg: Int, activeBg: Int) {
            fillPaint.color = fg
            bgPaint.color = activeBg
            invalidate()
        }

        fun setActive(isActive: Boolean) {
            if (active != isActive) {
                active = isActive
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (active) {
                canvas.drawCircle(cx, cy, 18f * d, bgPaint)
            }
            // Exact measurement from gboard.png: 2x2 rounded squares, 8.5dp each, 2dp gap
            val cell = 8.2f * d
            val gap = 2.4f * d
            val total = cell * 2 + gap
            val sx = cx - total / 2f
            val sy = cy - total / 2f
            val cr = 2.0f * d
            for (r in 0..1) {
                for (c in 0..1) {
                    val l = sx + c * (cell + gap)
                    val t = sy + r * (cell + gap)
                    rect.set(l, t, l + cell, t + cell)
                    canvas.drawRoundRect(rect, cr, cr, fillPaint)
                }
            }
        }
    }

    /** Material Symbols Clipboard icon inside the centered suggestion pill. */
    private class ClipboardIconView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()

        fun setColor(fg: Int) {
            stroke.color = fg
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            rect.set(cx - 5.5f * d, cy - 5.5f * d, cx + 5.5f * d, cy + 7.2f * d)
            canvas.drawRoundRect(rect, 1.8f * d, 1.8f * d, stroke)
            rect.set(cx - 2.6f * d, cy - 7.3f * d, cx + 2.6f * d, cy - 4.3f * d)
            canvas.drawRoundRect(rect, 1.2f * d, 1.2f * d, stroke)
            canvas.drawLine(cx - 2.8f * d, cy - 0.5f * d, cx + 2.8f * d, cy - 0.5f * d, stroke)
            canvas.drawLine(cx - 2.8f * d, cy + 2.8f * d, cx + 2.8f * d, cy + 2.8f * d, stroke)
        }
    }

    /** Material Symbols Microphone inside a 36dp circle (matches gboard.png right slot). */
    private class MicCircleView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fgStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * d
            strokeCap = Paint.Cap.ROUND
        }
        private val rect = RectF()

        fun setColors(circleBg: Int, fg: Int) {
            circlePaint.color = circleBg
            fgFill.color = fg
            fgStroke.color = fg
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, 18f * d, circlePaint)

            // Mic capsule
            rect.set(cx - 2.8f * d, cy - 8.2f * d, cx + 2.8f * d, cy + 1.0f * d)
            canvas.drawRoundRect(rect, 2.8f * d, 2.8f * d, fgFill)
            // Mic cradle arc
            rect.set(cx - 5.6f * d, cy - 3.5f * d, cx + 5.6f * d, cy + 4.4f * d)
            canvas.drawArc(rect, 0f, 180f, false, fgStroke)
            // Stem & base
            canvas.drawLine(cx, cy + 4.5f * d, cx, cy + 8.0f * d, fgStroke)
            canvas.drawLine(cx - 3.2f * d, cy + 8.0f * d, cx + 3.2f * d, cy + 8.0f * d, fgStroke)
        }
    }
}
