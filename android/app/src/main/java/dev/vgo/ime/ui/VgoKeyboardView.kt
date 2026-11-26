package dev.vgo.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.abs

/**
 * Pixel-calibrated custom Canvas keyboard view matching Gboard tablet 1600x2560 geometry,
 * Google Sans typography (loaded from system fonts when available), Material Symbols vector icons,
 * and Gboard-style floating key preview + long-press alternate symbol picker.
 */
class VgoKeyboardView(
    context: Context,
    private val listener: KeyListener,
) : View(context) {

    interface KeyListener {
        fun onKeyAction(action: KeyAction)
        fun onLongPressText(text: String)
        fun onSpaceSwipeCursor(steps: Int)
        fun onSpaceLongPress()
        fun onBackspaceSwipeDeleteWord()
        fun onShiftLongPress()
    }

    private data class PlacedKey(
        val spec: KeySpec,
        val rect: RectF,
        val rowIndex: Int,
        val colIndex: Int,
    )

    private data class ActivePointer(
        val pointerId: Int,
        val key: PlacedKey,
        val downX: Float,
        val downY: Float,
        var lastCursorX: Float = downX,
        var spaceSwiping: Boolean = false,
        var backspaceWordDeleted: Boolean = false,
        var longPressActive: Boolean = false,
        var selectedPopupIndex: Int = 0,
        var repeatFired: Boolean = false,
        var longPressRunnable: Runnable? = null,
        var repeatRunnable: Runnable? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density

    private var palette: KeyboardPalette = KeyboardTheme.resolve(context)
    private var rows: List<List<KeySpec>> = emptyList()
    private var placedKeys: List<PlacedKey> = emptyList()
    private var heightPercent: Int = 100
    private var hapticEnabled: Boolean = true
    private var swipeSpaceCursor: Boolean = true

    private val activePointers = mutableMapOf<Int, ActivePointer>()

    private val gboardTypeface: Typeface = loadSystemGoogleSans()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = gboardTypeface
    }
    private val funcTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = gboardTypeface
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPath = Path()
    private val iconRect = RectF()

    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupSelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = gboardTypeface
    }
    private val popupRect = RectF()
    private val popupItemRect = RectF()
    private val previewRect = RectF()

    // Exact dimensions measured from ~/Projects/vgo-ref/gboard.png (1600x2560 @ 320dpi):
    private val keyCornerRadius = 9f * density
    private val keyGapX = 10f * density
    private val keyGapY = 10f * density
    private val padHorizontal = 12f * density
    private val padTop = 8f * density
    private val padBottom = 12f * density
    private val spaceSwipeStepPx = 18f * density
    private val backspaceWordSwipePx = 38f * density

    fun updateState(
        newRows: List<List<KeySpec>>,
        newPalette: KeyboardPalette,
        newHeightPercent: Int,
        newHapticEnabled: Boolean,
        newSwipeSpaceCursor: Boolean,
    ) {
        val clampedHeight = newHeightPercent.coerceIn(75, 130)
        val geometryChanged = rows.size != newRows.size ||
            heightPercent != clampedHeight ||
            rows.zip(newRows).any { (a, b) -> a.size != b.size }
        rows = newRows
        palette = newPalette
        heightPercent = clampedHeight
        hapticEnabled = newHapticEnabled
        swipeSpaceCursor = newSwipeSpaceCursor
        if (geometryChanged) {
            cancelAllPointers()
            requestLayout()
        }
        rebuildPlacedKeys(width, height)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rowCount = rows.size.coerceAtLeast(4)
        val baseRowDp = if (isLandscape) {
            if (rowCount >= 5) 44f else 52f
        } else {
            if (rowCount >= 5) 49.5f else 56f
        }
        val scaledRowPx = baseRowDp * (heightPercent / 100f) * density
        val totalHeight = (padTop + padBottom + rowCount * scaledRowPx + (rowCount - 1) * keyGapY).toInt()
        setMeasuredDimension(w, totalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPlacedKeys(w, h)
    }

    private fun rebuildPlacedKeys(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || rows.isEmpty()) {
            placedKeys = emptyList()
            return
        }
        val rowCount = rows.size
        val availW = (w - padHorizontal * 2f).coerceAtLeast(1f)
        val availH = (h - padTop - padBottom - keyGapY * (rowCount - 1)).coerceAtLeast(1f)
        val rowH = availH / rowCount

        val out = ArrayList<PlacedKey>(64)
        var y = padTop
        rows.forEachIndexed { rIdx, row ->
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
            val gapCount = (row.size - 1).coerceAtLeast(0)
            val unitW = (availW - gapCount * keyGapX) / totalWeight
            var x = padHorizontal
            row.forEachIndexed { cIdx, spec ->
                val kw = unitW * spec.weight
                if (spec.kind != KeyKind.SPACER) {
                    out += PlacedKey(spec, RectF(x, y, x + kw, y + rowH), rIdx, cIdx)
                }
                x += kw + keyGapX
            }
            y += rowH + keyGapY
        }
        placedKeys = out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Bounded rect (NEVER drawColor): parent VgoInputContainer uses clipChildren=false for Row 0 key previews,
        // so drawColor() would paint over VgoToolbarView above us.
        bgPaint.color = palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val pressedKeys = activePointers.values.mapTo(HashSet()) { it.key }
        shadowPaint.color = palette.keyShadow
        val drawShadow = palette.keyShadow != android.graphics.Color.TRANSPARENT

        textPaint.textSize = 23.5f * density
        textPaint.color = palette.keyText

        for (pk in placedKeys) {
            val pressed = pk in pressedKeys
            val r = pk.rect

            bgPaint.color = when (pk.spec.kind) {
                KeyKind.CHAR, KeyKind.SPACE -> if (pressed) palette.keyPressedBg else palette.keyBg
                KeyKind.FUNCTION -> if (pressed) palette.funcKeyPressedBg else palette.funcKeyBg
                KeyKind.ACTION -> if (pressed) palette.actionKeyPressedBg else palette.actionKeyBg
                KeyKind.SPACER -> continue
            }

            val radius = when {
                pk.spec.action == KeyAction.Enter -> r.height() / 2f
                else -> keyCornerRadius
            }

            if (drawShadow && !pressed) {
                canvas.drawRoundRect(
                    r.left,
                    r.top + 1.2f * density,
                    r.right,
                    r.bottom + 1.2f * density,
                    radius,
                    radius,
                    shadowPaint,
                )
            }
            canvas.drawRoundRect(r, radius, radius, bgPaint)

            if (pk.spec.icon != KeyIcon.NONE) {
                val fg = when (pk.spec.kind) {
                    KeyKind.ACTION -> palette.actionKeyText
                    KeyKind.FUNCTION -> palette.funcKeyText
                    else -> palette.keyText
                }
                drawKeyIcon(canvas, pk.spec.icon, r.centerX(), r.centerY(), fg)
            } else {
                val paint = when (pk.spec.kind) {
                    KeyKind.SPACE -> {
                        funcTextPaint.textSize = 15.5f * density
                        funcTextPaint.color = palette.keyText
                        funcTextPaint
                    }
                    KeyKind.FUNCTION -> {
                        funcTextPaint.textSize = 15.5f * density
                        funcTextPaint.color = palette.funcKeyText
                        funcTextPaint
                    }
                    KeyKind.ACTION -> {
                        funcTextPaint.textSize = 15.5f * density
                        funcTextPaint.color = palette.actionKeyText
                        funcTextPaint
                    }
                    else -> textPaint
                }
                val cy = r.centerY() - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText(pk.spec.label, r.centerX(), cy, paint)
            }
        }

        // Gboard-style enlarged key preview bubble above pressed character key
        for (ap in activePointers.values) {
            if (ap.longPressActive && ap.key.spec.longPressOptions.isNotEmpty()) {
                drawLongPressPopup(canvas, ap)
            } else if (!ap.longPressActive && ap.key.spec.kind == KeyKind.CHAR && ap.key.spec.label.isNotEmpty()) {
                drawKeyPreviewBubble(canvas, ap.key)
            }
        }
    }

    private fun drawKeyPreviewBubble(canvas: Canvas, pk: PlacedKey) {
        val r = pk.rect
        val bw = maxOf(r.width() * 1.12f, 54f * density)
        val bh = 58f * density
        var left = r.centerX() - bw / 2f
        left = left.coerceIn(4f * density, (width - bw - 4f * density).coerceAtLeast(4f * density))
        val bottom = r.top - 8f * density
        val top = bottom - bh
        previewRect.set(left, top, left + bw, bottom)

        shadowPaint.color = android.graphics.Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(
            previewRect.left,
            previewRect.top + 3f * density,
            previewRect.right,
            previewRect.bottom + 3f * density,
            14f * density,
            14f * density,
            shadowPaint,
        )
        popupPaint.color = palette.popupBg
        canvas.drawRoundRect(previewRect, 14f * density, 14f * density, popupPaint)

        popupTextPaint.color = palette.popupText
        popupTextPaint.textSize = 30f * density
        val cy = previewRect.centerY() - (popupTextPaint.descent() + popupTextPaint.ascent()) / 2f
        canvas.drawText(pk.spec.label, previewRect.centerX(), cy, popupTextPaint)
    }

    private fun drawKeyIcon(canvas: Canvas, icon: KeyIcon, cx: Float, cy: Float, color: Int) {
        val u = density
        iconStrokePaint.color = color
        iconStrokePaint.strokeWidth = 2.0f * u
        iconFillPaint.color = color

        when (icon) {
            KeyIcon.BACKSPACE -> {
                // Material Symbols Backspace
                val w = 22f * u
                val h = 16f * u
                val left = cx - w / 2f
                val right = cx + w / 2f
                val top = cy - h / 2f
                val bottom = cy + h / 2f
                val tip = left + 5.8f * u
                iconPath.reset()
                iconPath.moveTo(tip, top)
                iconPath.lineTo(right - 2f * u, top)
                iconPath.lineTo(right, top + 2f * u)
                iconPath.lineTo(right, bottom - 2f * u)
                iconPath.lineTo(right - 2f * u, bottom)
                iconPath.lineTo(tip, bottom)
                iconPath.lineTo(left, cy)
                iconPath.close()
                canvas.drawPath(iconPath, iconStrokePaint)
                val xCx = cx + 2.4f * u
                val xr = 3.3f * u
                canvas.drawLine(xCx - xr, cy - xr, xCx + xr, cy + xr, iconStrokePaint)
                canvas.drawLine(xCx + xr, cy - xr, xCx - xr, cy + xr, iconStrokePaint)
            }

            KeyIcon.TAB -> {
                // Material Symbols Keyboard Tab (->|)
                val half = 8.5f * u
                canvas.drawLine(cx - half, cy, cx + 4.2f * u, cy, iconStrokePaint)
                canvas.drawLine(cx + 0.2f * u, cy - 4.4f * u, cx + 4.6f * u, cy, iconStrokePaint)
                canvas.drawLine(cx + 0.2f * u, cy + 4.4f * u, cx + 4.6f * u, cy, iconStrokePaint)
                canvas.drawLine(cx + half, cy - 5.5f * u, cx + half, cy + 5.5f * u, iconStrokePaint)
            }

            KeyIcon.CAPS_LOCK, KeyIcon.CAPS_LOCK_ACTIVE -> {
                // Material Symbols Keyboard Capslock (chevron over bar)
                iconPath.reset()
                iconPath.moveTo(cx - 5.8f * u, cy + 0.2f * u)
                iconPath.lineTo(cx, cy - 5.8f * u)
                iconPath.lineTo(cx + 5.8f * u, cy + 0.2f * u)
                if (icon == KeyIcon.CAPS_LOCK_ACTIVE) {
                    iconPath.close()
                    canvas.drawPath(iconPath, iconFillPaint)
                } else {
                    canvas.drawPath(iconPath, iconStrokePaint)
                }
                canvas.drawLine(cx - 5.5f * u, cy + 5.2f * u, cx + 5.5f * u, cy + 5.2f * u, iconStrokePaint)
            }

            KeyIcon.SHIFT_OFF, KeyIcon.SHIFT_ON -> {
                // Material Symbols Shift (hollow/filled arrow)
                iconPath.reset()
                iconPath.moveTo(cx, cy - 8.5f * u)
                iconPath.lineTo(cx + 8.2f * u, cy + 0.5f * u)
                iconPath.lineTo(cx + 3.6f * u, cy + 0.5f * u)
                iconPath.lineTo(cx + 3.6f * u, cy + 7.5f * u)
                iconPath.lineTo(cx - 3.6f * u, cy + 7.5f * u)
                iconPath.lineTo(cx - 3.6f * u, cy + 0.5f * u)
                iconPath.lineTo(cx - 8.2f * u, cy + 0.5f * u)
                iconPath.close()
                if (icon == KeyIcon.SHIFT_ON) {
                    canvas.drawPath(iconPath, iconFillPaint)
                } else {
                    canvas.drawPath(iconPath, iconStrokePaint)
                }
            }

            KeyIcon.EMOJI -> {
                // Material Symbols Mood
                val r = 9.5f * u
                canvas.drawCircle(cx, cy, r, iconStrokePaint)
                canvas.drawCircle(cx - 3.2f * u, cy - 2.3f * u, 1.3f * u, iconFillPaint)
                canvas.drawCircle(cx + 3.2f * u, cy - 2.3f * u, 1.3f * u, iconFillPaint)
                iconRect.set(cx - 4.8f * u, cy - 3.2f * u, cx + 4.8f * u, cy + 5.2f * u)
                canvas.drawArc(iconRect, 20f, 140f, false, iconStrokePaint)
            }

            KeyIcon.GLOBE -> {
                // Material Symbols Language (Gboard VN<->EN globe switch)
                val r = 9.5f * u
                canvas.drawCircle(cx, cy, r, iconStrokePaint)
                iconRect.set(cx - 4.3f * u, cy - r, cx + 4.3f * u, cy + r)
                canvas.drawOval(iconRect, iconStrokePaint)
                canvas.drawLine(cx - r, cy, cx + r, cy, iconStrokePaint)
                canvas.drawLine(cx - r * 0.85f, cy - r * 0.45f, cx + r * 0.85f, cy - r * 0.45f, iconStrokePaint)
                canvas.drawLine(cx - r * 0.85f, cy + r * 0.45f, cx + r * 0.85f, cy + r * 0.45f, iconStrokePaint)
            }

            KeyIcon.ARROW_LEFT -> {
                // Material Symbols Arrow Left (solid triangle)
                iconPath.reset()
                iconPath.moveTo(cx - 4.5f * u, cy)
                iconPath.lineTo(cx + 3.5f * u, cy - 5.5f * u)
                iconPath.lineTo(cx + 3.5f * u, cy + 5.5f * u)
                iconPath.close()
                canvas.drawPath(iconPath, iconFillPaint)
            }

            KeyIcon.ARROW_RIGHT -> {
                // Material Symbols Arrow Right (solid triangle)
                iconPath.reset()
                iconPath.moveTo(cx + 4.5f * u, cy)
                iconPath.lineTo(cx - 3.5f * u, cy - 5.5f * u)
                iconPath.lineTo(cx - 3.5f * u, cy + 5.5f * u)
                iconPath.close()
                canvas.drawPath(iconPath, iconFillPaint)
            }

            KeyIcon.SEARCH -> {
                // Material Symbols Search
                val r = 7.0f * u
                val ox = cx - 2.0f * u
                val oy = cy - 2.0f * u
                canvas.drawCircle(ox, oy, r, iconStrokePaint)
                canvas.drawLine(ox + 5.0f * u, oy + 5.0f * u, ox + 10.8f * u, oy + 10.8f * u, iconStrokePaint)
            }

            KeyIcon.SEND -> {
                iconPath.reset()
                iconPath.moveTo(cx - 8f * u, cy - 7f * u)
                iconPath.lineTo(cx + 9f * u, cy)
                iconPath.lineTo(cx - 8f * u, cy + 7f * u)
                iconPath.lineTo(cx - 5f * u, cy)
                iconPath.close()
                canvas.drawPath(iconPath, iconFillPaint)
            }

            KeyIcon.GO, KeyIcon.NEXT -> {
                canvas.drawLine(cx - 7.5f * u, cy, cx + 7f * u, cy, iconStrokePaint)
                canvas.drawLine(cx + 2f * u, cy - 5.2f * u, cx + 7.5f * u, cy, iconStrokePaint)
                canvas.drawLine(cx + 2f * u, cy + 5.2f * u, cx + 7.5f * u, cy, iconStrokePaint)
            }

            KeyIcon.DONE -> {
                iconPath.reset()
                iconPath.moveTo(cx - 7.5f * u, cy + 0.5f * u)
                iconPath.lineTo(cx - 2.2f * u, cy + 5.8f * u)
                iconPath.lineTo(cx + 8f * u, cy - 5.5f * u)
                canvas.drawPath(iconPath, iconStrokePaint)
            }

            KeyIcon.ENTER -> {
                iconPath.reset()
                iconPath.moveTo(cx + 7.5f * u, cy - 5.5f * u)
                iconPath.lineTo(cx + 7.5f * u, cy + 2f * u)
                iconPath.lineTo(cx - 7f * u, cy + 2f * u)
                canvas.drawPath(iconPath, iconStrokePaint)
                canvas.drawLine(cx - 2.5f * u, cy - 2.5f * u, cx - 7.3f * u, cy + 2f * u, iconStrokePaint)
                canvas.drawLine(cx - 2.5f * u, cy + 6.5f * u, cx - 7.3f * u, cy + 2f * u, iconStrokePaint)
            }

            KeyIcon.NONE -> Unit
        }
    }

    private fun drawLongPressPopup(canvas: Canvas, ap: ActivePointer) {
        val options = ap.key.spec.longPressOptions
        val cellW = 44f * density
        val cellH = 44f * density
        val pad = 5f * density
        val totalW = options.size * cellW + pad * 2f
        val totalH = cellH + pad * 2f

        val anchor = ap.key.rect
        var left = anchor.centerX() - totalW / 2f
        left = left.coerceIn(4f * density, (width - totalW - 4f * density).coerceAtLeast(4f * density))
        val top = anchor.top - totalH - 8f * density

        popupRect.set(left, top, left + totalW, top + totalH)

        shadowPaint.color = android.graphics.Color.argb(105, 0, 0, 0)
        canvas.drawRoundRect(
            popupRect.left,
            popupRect.top + 3f * density,
            popupRect.right,
            popupRect.bottom + 3f * density,
            12f * density,
            12f * density,
            shadowPaint,
        )
        popupPaint.color = palette.popupBg
        canvas.drawRoundRect(popupRect, 12f * density, 12f * density, popupPaint)

        popupSelPaint.color = palette.popupSelectedBg
        popupTextPaint.color = palette.popupText
        popupTextPaint.textSize = 19.5f * density

        options.forEachIndexed { idx, opt ->
            val ix = left + pad + idx * cellW
            val iy = top + pad
            popupItemRect.set(ix, iy, ix + cellW, iy + cellH)
            if (idx == ap.selectedPopupIndex) {
                canvas.drawRoundRect(popupItemRect, 8f * density, 8f * density, popupSelPaint)
            }
            val cy = popupItemRect.centerY() - (popupTextPaint.descent() + popupTextPaint.ascent()) / 2f
            canvas.drawText(opt, popupItemRect.centerX(), cy, popupTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                handlePointerDown(event.getPointerId(idx), event.getX(idx), event.getY(idx))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val ap = activePointers[event.getPointerId(i)] ?: continue
                    handlePointerMove(ap, event.getX(i), event.getY(i))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event.getPointerId(event.actionIndex))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAllPointers()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        val hit = findKeyAt(x, y) ?: return

        // Fast two-thumb typing: commit held char key immediately when next finger lands.
        if (hit.spec.kind == KeyKind.CHAR || hit.spec.kind == KeyKind.SPACE) {
            val toCommit = activePointers.values.filter {
                it.key.spec.kind == KeyKind.CHAR && !it.longPressActive
            }
            for (prev in toCommit) {
                clearTimers(prev)
                activePointers.remove(prev.pointerId)
                fireKeyAction(prev.key.spec.action)
            }
        }

        val ap = ActivePointer(pointerId = pointerId, key = hit, downX = x, downY = y)
        activePointers[pointerId] = ap
        triggerHaptic()

        when (hit.spec.action) {
            KeyAction.Backspace -> {
                val repeater = object : Runnable {
                    override fun run() {
                        if (activePointers[pointerId] !== ap || ap.backspaceWordDeleted) return
                        ap.repeatFired = true
                        triggerHaptic()
                        listener.onKeyAction(KeyAction.Backspace)
                        handler.postDelayed(this, 55L)
                    }
                }
                ap.repeatRunnable = repeater
                handler.postDelayed(repeater, 380L)
            }
            KeyAction.Shift -> {
                val lp = Runnable {
                    if (activePointers[pointerId] !== ap) return@Runnable
                    ap.longPressActive = true
                    triggerHaptic()
                    listener.onShiftLongPress()
                    invalidate()
                }
                ap.longPressRunnable = lp
                handler.postDelayed(lp, 380L)
            }
            KeyAction.Space, KeyAction.ToggleVn -> {
                // Long-press Spacebar (or Globe key) opens Android's system IME switcher picker (like Gboard).
                val lp = Runnable {
                    if (activePointers[pointerId] !== ap || ap.spaceSwiping) return@Runnable
                    ap.longPressActive = true
                    triggerHaptic()
                    listener.onSpaceLongPress()
                    invalidate()
                }
                ap.longPressRunnable = lp
                handler.postDelayed(lp, 450L)
            }
            else -> {
                if (hit.spec.longPressOptions.isNotEmpty()) {
                    val lp = Runnable {
                        if (activePointers[pointerId] !== ap) return@Runnable
                        ap.longPressActive = true
                        ap.selectedPopupIndex = 0
                        triggerHaptic()
                        invalidate()
                    }
                    ap.longPressRunnable = lp
                    handler.postDelayed(lp, 340L)
                }
            }
        }
        invalidate()
    }

    private fun handlePointerMove(ap: ActivePointer, x: Float, y: Float) {
        if (ap.longPressActive && ap.key.spec.longPressOptions.isNotEmpty()) {
            val options = ap.key.spec.longPressOptions
            if (options.size > 1 && popupRect.width() > 0f) {
                val relX = (x - popupRect.left).coerceIn(0f, popupRect.width() - 1f)
                val idx = ((relX / popupRect.width()) * options.size).toInt().coerceIn(0, options.lastIndex)
                if (idx != ap.selectedPopupIndex) {
                    ap.selectedPopupIndex = idx
                    triggerHaptic()
                    invalidate()
                }
            }
            return
        }

        if (ap.key.spec.action == KeyAction.Space && swipeSpaceCursor && !ap.longPressActive) {
            val dxTotal = x - ap.downX
            if (!ap.spaceSwiping && abs(dxTotal) >= spaceSwipeStepPx) {
                clearTimers(ap)
                ap.spaceSwiping = true
                ap.lastCursorX = ap.downX
            }
            if (ap.spaceSwiping) {
                val delta = x - ap.lastCursorX
                val steps = (delta / spaceSwipeStepPx).toInt()
                if (steps != 0) {
                    ap.lastCursorX += steps * spaceSwipeStepPx
                    triggerHaptic()
                    listener.onSpaceSwipeCursor(steps)
                }
            }
            return
        }

        if (ap.key.spec.action == KeyAction.Backspace && !ap.backspaceWordDeleted && !ap.repeatFired) {
            val dx = x - ap.downX
            if (dx <= -backspaceWordSwipePx) {
                clearTimers(ap)
                ap.backspaceWordDeleted = true
                triggerHaptic()
                listener.onBackspaceSwipeDeleteWord()
            }
        }
    }

    private fun handlePointerUp(pointerId: Int) {
        val ap = activePointers.remove(pointerId) ?: return
        clearTimers(ap)

        when {
            ap.longPressActive && ap.key.spec.longPressOptions.isNotEmpty() -> {
                val chosen = ap.key.spec.longPressOptions.getOrNull(ap.selectedPopupIndex)
                if (chosen != null) listener.onLongPressText(chosen)
            }
            ap.longPressActive && ap.key.spec.action == KeyAction.Shift -> Unit
            ap.longPressActive && (ap.key.spec.action == KeyAction.Space || ap.key.spec.action == KeyAction.ToggleVn) -> Unit
            ap.spaceSwiping -> Unit
            ap.backspaceWordDeleted || ap.repeatFired -> Unit
            else -> fireKeyAction(ap.key.spec.action)
        }
        invalidate()
    }

    private fun fireKeyAction(action: KeyAction) {
        if (action != KeyAction.None) listener.onKeyAction(action)
    }

    private fun findKeyAt(x: Float, y: Float): PlacedKey? {
        for (pk in placedKeys) {
            if (pk.rect.contains(x, y)) return pk
        }
        var best: PlacedKey? = null
        var bestDist = Float.MAX_VALUE
        for (pk in placedKeys) {
            val cx = x.coerceIn(pk.rect.left, pk.rect.right)
            val cy = y.coerceIn(pk.rect.top, pk.rect.bottom)
            val dx = x - cx
            val dy = y - cy
            val d2 = dx * dx + dy * dy
            if (d2 < bestDist) {
                bestDist = d2
                best = pk
            }
        }
        val maxMargin = 18f * density
        return if (bestDist <= maxMargin * maxMargin) best else null
    }

    private fun clearTimers(ap: ActivePointer) {
        ap.longPressRunnable?.let { handler.removeCallbacks(it) }
        ap.repeatRunnable?.let { handler.removeCallbacks(it) }
        ap.longPressRunnable = null
        ap.repeatRunnable = null
    }

    private fun cancelAllPointers() {
        for (ap in activePointers.values) clearTimers(ap)
        activePointers.clear()
    }

    private fun triggerHaptic() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDetachedFromWindow() {
        cancelAllPointers()
        super.onDetachedFromWindow()
    }

    companion object {
        private var cachedGoogleSans: Typeface? = null

        fun loadSystemGoogleSans(): Typeface {
            cachedGoogleSans?.let { return it }
            val candidates = listOf(
                "/product/fonts/GoogleSansFlex-Regular.ttf",
                "/product/fonts/GoogleSans-Regular.ttf",
                "/system/fonts/GoogleSans-Regular.ttf",
                "/system/fonts/Roboto-Regular.ttf",
            )
            for (path in candidates) {
                val f = File(path)
                if (f.isFile && f.canRead()) {
                    runCatching { Typeface.createFromFile(f) }.getOrNull()?.let { tf ->
                        cachedGoogleSans = tf
                        return tf
                    }
                }
            }
            return Typeface.create("sans-serif", Typeface.NORMAL).also { cachedGoogleSans = it }
        }
    }
}
