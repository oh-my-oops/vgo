package dev.vgo.ime.ui

import android.view.inputmethod.EditorInfo
import dev.vgo.ime.engine.InputMethodMode

enum class KeyboardPage {
    ALPHA,
    SYMBOLS_1,
    SYMBOLS_2,
    NUMPAD,
}

enum class ShiftState {
    OFF,
    ONCE,
    CAPS_LOCK,
}

enum class KeyKind {
    CHAR,
    FUNCTION,
    ACTION,
    SPACE,
    SPACER,
}

enum class KeyIcon {
    NONE,
    BACKSPACE,
    TAB,
    CAPS_LOCK,
    CAPS_LOCK_ACTIVE,
    SHIFT_OFF,
    SHIFT_ON,
    EMOJI,
    GLOBE,
    ARROW_LEFT,
    ARROW_RIGHT,
    ENTER,
    SEARCH,
    SEND,
    GO,
    NEXT,
    DONE,
}

sealed interface KeyAction {
    data class Text(val value: String, val isNumpad: Boolean = false) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object CapsLock : KeyAction
    data object Tab : KeyAction
    data object Space : KeyAction
    data object SwitchToSymbols1 : KeyAction
    data object SwitchToSymbols2 : KeyAction
    data object SwitchToAlpha : KeyAction
    data object CursorLeft : KeyAction
    data object CursorRight : KeyAction
    data object ToggleEmoji : KeyAction
    data object HideKeyboard : KeyAction
    data object ToggleVn : KeyAction
    data object None : KeyAction
}

data class KeySpec(
    val label: String,
    val action: KeyAction,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.CHAR,
    val icon: KeyIcon = KeyIcon.NONE,
    val hint: String = "",
    val longPressOptions: List<String> = emptyList(),
)

object KeyboardLayoutBuilder {

    private val enDigitLongPress = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")

    fun buildRows(
        page: KeyboardPage,
        shiftState: ShiftState,
        mode: InputMethodMode,
        vnEnabled: Boolean,
        showNumberRow: Boolean,
        showVniHints: Boolean,
        split: Boolean,
        isLandscape: Boolean,
        imeAction: Int,
        isMultiline: Boolean,
    ): List<List<KeySpec>> {
        return when (page) {
            KeyboardPage.ALPHA -> buildAlphaRows(
                shiftState = shiftState,
                vnEnabled = vnEnabled,
                showNumberRow = showNumberRow,
                split = split,
                isLandscape = isLandscape,
                imeAction = imeAction,
                isMultiline = isMultiline,
            )
            KeyboardPage.SYMBOLS_1 -> buildSymbols1Rows(split, isLandscape, imeAction, isMultiline)
            KeyboardPage.SYMBOLS_2 -> buildSymbols2Rows(split, isLandscape, imeAction, isMultiline)
            KeyboardPage.NUMPAD -> buildNumpadRows(imeAction, isMultiline)
        }
    }

    /**
     * Exact pixel-calibrated Gboard tablet 5-row layout (measured from 1600x2560 gboard.png):
     * 1.000 weight unit = 55.0dp (110px), gapX = 10dp (20px), side pad = 12dp (24px):
     * - Row 0 (12 keys): `(1.000) | 1..0 (1.000x10) | ⌫ (1.109)
     * - Row 1 (12 keys): →|(1.055) | q..p (1.000x10) | \(1.055)
     * - Row 2 (11 keys): ⇪(1.345) | a..l (1.000x9) | Enter pill(1.945)
     * - Row 3 (11 keys): ⇧(1.927) | z..m , . (1.000x9) | ⇧(1.364)
     * - Row 4 ( 6 keys): ?123(1.464) | ☺(1.464) | Space(7.527) | ◀(0.873) | ▶(0.873) | ?123(1.000)
     */
    private fun buildAlphaRows(
        shiftState: ShiftState,
        vnEnabled: Boolean,
        showNumberRow: Boolean,
        split: Boolean,
        isLandscape: Boolean,
        imeAction: Int,
        isMultiline: Boolean,
    ): List<List<KeySpec>> {
        val upper = shiftState != ShiftState.OFF
        val gap = if (split) (if (isLandscape) 2.2f else 1.5f) else 0f

        fun letter(ch: Char, lp: List<String>): KeySpec {
            val shown = if (upper) ch.uppercaseChar().toString() else ch.toString()
            return KeySpec(label = shown, action = KeyAction.Text(shown), longPressOptions = lp)
        }

        val rows = mutableListOf<List<KeySpec>>()

        // Row 0: ` | 1 2 3 4 5 | 6 7 8 9 0 | Backspace
        if (showNumberRow) {
            val left = buildList {
                add(KeySpec("`", KeyAction.Text("`"), weight = 1.000f, longPressOptions = listOf("~", "`")))
                for (i in 0 until 5) {
                    val d = "${i + 1}"
                    add(KeySpec(d, KeyAction.Text(d), weight = 1.000f, longPressOptions = listOf(enDigitLongPress[i])))
                }
            }
            val right = buildList {
                for (i in 5 until 10) {
                    val d = if (i == 9) "0" else "${i + 1}"
                    add(KeySpec(d, KeyAction.Text(d), weight = 1.000f, longPressOptions = listOf(enDigitLongPress[i])))
                }
                add(
                    KeySpec(
                        label = "⌫",
                        action = KeyAction.Backspace,
                        weight = 1.109f,
                        kind = KeyKind.FUNCTION,
                        icon = KeyIcon.BACKSPACE,
                    )
                )
            }
            rows += combineWithSplit(left, right, gap)
        }

        // Row 1: Tab (→|) | q w e r t | y u i o p | \
        val r1Left = listOf(
            KeySpec("⇥", KeyAction.Tab, weight = 1.055f, kind = KeyKind.FUNCTION, icon = KeyIcon.TAB),
            letter('q', listOf("1", "!")),
            letter('w', listOf("2", "@")),
            letter('e', listOf("3", "#", "€")),
            letter('r', listOf("4", "$")),
            letter('t', listOf("5", "%")),
        )
        val r1Right = listOf(
            letter('y', listOf("6", "^")),
            letter('u', listOf("7", "&")),
            letter('i', listOf("8", "*")),
            letter('o', listOf("9", "(")),
            letter('p', listOf("0", ")")),
            if (showNumberRow) {
                KeySpec("\\", KeyAction.Text("\\"), weight = 1.055f, longPressOptions = listOf("|", "/", "\\"))
            } else {
                KeySpec("⌫", KeyAction.Backspace, weight = 1.055f, kind = KeyKind.FUNCTION, icon = KeyIcon.BACKSPACE)
            },
        )
        rows += combineWithSplit(r1Left, r1Right, gap)

        // Row 2: CapsLock (⇪) | a s d f g | h j k l | Enter pill
        val capsActive = shiftState == ShiftState.CAPS_LOCK
        val r2Left = listOf(
            KeySpec(
                label = "⇪",
                action = KeyAction.CapsLock,
                weight = 1.345f,
                kind = if (capsActive) KeyKind.ACTION else KeyKind.FUNCTION,
                icon = if (capsActive) KeyIcon.CAPS_LOCK_ACTIVE else KeyIcon.CAPS_LOCK,
            ),
            letter('a', listOf("@", "~")),
            letter('s', listOf("#", "§")),
            letter('d', listOf("₫", "$")),
            letter('f', listOf("_", "{")),
            letter('g', listOf("&", "}")),
        )
        val r2Right = listOf(
            letter('h', listOf("-", "[")),
            letter('j', listOf("+", "]")),
            letter('k', listOf("(", ":")),
            letter('l', listOf(")", ";")),
            KeySpec(
                label = "↵",
                action = KeyAction.Enter,
                weight = 1.945f,
                kind = KeyKind.ACTION,
                icon = enterIcon(imeAction, isMultiline),
            ),
        )
        rows += combineWithSplit(r2Left, r2Right, gap)

        // Row 3: Shift | z x c v | b n m , . | Shift
        val shiftOn = shiftState != ShiftState.OFF
        val shiftIcon = if (shiftOn) KeyIcon.SHIFT_ON else KeyIcon.SHIFT_OFF
        val shiftKind = if (shiftOn) KeyKind.ACTION else KeyKind.FUNCTION
        val r3Left = listOf(
            KeySpec("⇧", KeyAction.Shift, weight = 1.927f, kind = shiftKind, icon = shiftIcon),
            letter('z', listOf("*", "`")),
            letter('x', listOf("\"", "_")),
            letter('c', listOf("'", "-")),
            letter('v', listOf(":", "=")),
        )
        val r3Right = listOf(
            letter('b', listOf(";", "+")),
            letter('n', listOf("!", "/")),
            letter('m', listOf("?", "\"")),
            KeySpec(",", KeyAction.Text(","), weight = 1.000f, longPressOptions = listOf(";", "<", "!")),
            KeySpec(".", KeyAction.Text("."), weight = 1.000f, longPressOptions = listOf(".", ",", "?", "!", ":", ";", "'", "\"", "-", "/", "…")),
            KeySpec("⇧", KeyAction.Shift, weight = 1.364f, kind = shiftKind, icon = shiftIcon),
        )
        rows += combineWithSplit(r3Left, r3Right, gap)

        // Row 4: ?123 | ☺ | 🌐 (VN<->EN) | Space ("Tiếng Việt" / "English") | ◀ | ▶ | ?123
        val spaceLabel = if (vnEnabled) "Tiếng Việt" else "English"
        if (split) {
            val r4Left = listOf(
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.250f, kind = KeyKind.FUNCTION),
                KeySpec("☺", KeyAction.ToggleEmoji, weight = 1.050f, kind = KeyKind.FUNCTION, icon = KeyIcon.EMOJI),
                KeySpec("🌐", KeyAction.ToggleVn, weight = 1.050f, kind = KeyKind.FUNCTION, icon = KeyIcon.GLOBE),
                KeySpec(spaceLabel, KeyAction.Space, weight = 3.000f, kind = KeyKind.SPACE),
            )
            val r4Right = listOf(
                KeySpec(spaceLabel, KeyAction.Space, weight = 3.600f, kind = KeyKind.SPACE),
                KeySpec("◀", KeyAction.CursorLeft, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_LEFT),
                KeySpec("▶", KeyAction.CursorRight, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_RIGHT),
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.000f, kind = KeyKind.FUNCTION),
            )
            rows += combineWithSplit(r4Left, r4Right, gap)
        } else {
            rows += listOf(
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.364f, kind = KeyKind.FUNCTION),
                KeySpec("☺", KeyAction.ToggleEmoji, weight = 1.100f, kind = KeyKind.FUNCTION, icon = KeyIcon.EMOJI),
                KeySpec("🌐", KeyAction.ToggleVn, weight = 1.100f, kind = KeyKind.FUNCTION, icon = KeyIcon.GLOBE),
                KeySpec(spaceLabel, KeyAction.Space, weight = 6.709f, kind = KeyKind.SPACE),
                KeySpec("◀", KeyAction.CursorLeft, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_LEFT),
                KeySpec("▶", KeyAction.CursorRight, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_RIGHT),
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.000f, kind = KeyKind.FUNCTION),
            )
        }

        return rows
    }

    private fun buildSymbols1Rows(
        split: Boolean,
        isLandscape: Boolean,
        imeAction: Int,
        isMultiline: Boolean,
    ): List<List<KeySpec>> {
        val gap = if (split) (if (isLandscape) 2.2f else 1.5f) else 0f
        fun sym(s: String, lp: List<String> = emptyList()) =
            KeySpec(s, KeyAction.Text(s), longPressOptions = lp)

        val r0 = combineWithSplit(
            listOf(sym("~", listOf("`")), sym("1"), sym("2"), sym("3"), sym("4"), sym("5")),
            listOf(
                sym("6"), sym("7"), sym("8"), sym("9"), sym("0"),
                KeySpec("⌫", KeyAction.Backspace, weight = 1.109f, kind = KeyKind.FUNCTION, icon = KeyIcon.BACKSPACE),
            ),
            gap,
        )
        val r1 = combineWithSplit(
            listOf(
                KeySpec("⇥", KeyAction.Tab, weight = 1.055f, kind = KeyKind.FUNCTION, icon = KeyIcon.TAB),
                sym("@"), sym("#"), sym("$", listOf("₫", "€", "£", "¥")), sym("_"), sym("&"),
            ),
            listOf(sym("-"), sym("+"), sym("("), sym(")"), sym("/"), KeySpec("|", KeyAction.Text("|"), weight = 1.055f)),
            gap,
        )
        val r2 = combineWithSplit(
            listOf(
                KeySpec("=\\<", KeyAction.SwitchToSymbols2, weight = 1.345f, kind = KeyKind.FUNCTION),
                sym("*"), sym("\""), sym("'"), sym(":"), sym(";"),
            ),
            listOf(
                sym("!"), sym("?"), sym("%"), sym("="),
                KeySpec("↵", KeyAction.Enter, weight = 1.945f, kind = KeyKind.ACTION, icon = enterIcon(imeAction, isMultiline)),
            ),
            gap,
        )
        val r3 = combineWithSplit(
            listOf(
                KeySpec("=\\<", KeyAction.SwitchToSymbols2, weight = 1.927f, kind = KeyKind.FUNCTION),
                sym("~"), sym("`"), sym("{"), sym("}"),
            ),
            listOf(
                sym("["), sym("]"), sym("<"), sym(">"), sym("^"),
                KeySpec("=\\<", KeyAction.SwitchToSymbols2, weight = 1.364f, kind = KeyKind.FUNCTION),
            ),
            gap,
        )
        val r4 = if (split) {
            combineWithSplit(
                listOf(
                    KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.464f, kind = KeyKind.FUNCTION),
                    sym(",", listOf(";")),
                    KeySpec("Space", KeyAction.Space, weight = 3.800f, kind = KeyKind.SPACE),
                ),
                listOf(
                    KeySpec("Space", KeyAction.Space, weight = 3.800f, kind = KeyKind.SPACE),
                    KeySpec("◀", KeyAction.CursorLeft, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_LEFT),
                    KeySpec("▶", KeyAction.CursorRight, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_RIGHT),
                    KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.000f, kind = KeyKind.FUNCTION),
                ),
                gap,
            )
        } else {
            listOf(
                KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.464f, kind = KeyKind.FUNCTION),
                KeySpec(",", KeyAction.Text(","), weight = 1.464f, kind = KeyKind.FUNCTION),
                KeySpec("Space", KeyAction.Space, weight = 7.527f, kind = KeyKind.SPACE),
                KeySpec("◀", KeyAction.CursorLeft, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_LEFT),
                KeySpec("▶", KeyAction.CursorRight, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_RIGHT),
                KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.000f, kind = KeyKind.FUNCTION),
            )
        }
        return listOf(r0, r1, r2, r3, r4)
    }

    private fun buildSymbols2Rows(
        split: Boolean,
        isLandscape: Boolean,
        imeAction: Int,
        isMultiline: Boolean,
    ): List<List<KeySpec>> {
        val gap = if (split) (if (isLandscape) 2.2f else 1.5f) else 0f
        fun sym(s: String) = KeySpec(s, KeyAction.Text(s))

        val r0 = combineWithSplit(
            listOf(sym("₫"), sym("€"), sym("£"), sym("¥"), sym("¢"), sym("°")),
            listOf(
                sym("•"), sym("○"), sym("●"), sym("□"), sym("■"),
                KeySpec("⌫", KeyAction.Backspace, weight = 1.109f, kind = KeyKind.FUNCTION, icon = KeyIcon.BACKSPACE),
            ),
            gap,
        )
        val r1 = combineWithSplit(
            listOf(
                KeySpec("⇥", KeyAction.Tab, weight = 1.055f, kind = KeyKind.FUNCTION, icon = KeyIcon.TAB),
                sym("±"), sym("×"), sym("÷"), sym("≠"), sym("≈"),
            ),
            listOf(sym("≤"), sym("≥"), sym("√"), sym("π"), sym("∞"), KeySpec("…", KeyAction.Text("…"), weight = 1.055f)),
            gap,
        )
        val r2 = combineWithSplit(
            listOf(
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.345f, kind = KeyKind.FUNCTION),
                sym("©"), sym("®"), sym("™"), sym("§"), sym("¶"),
            ),
            listOf(
                sym("«"), sym("»"), sym("“"), sym("”"),
                KeySpec("↵", KeyAction.Enter, weight = 1.945f, kind = KeyKind.ACTION, icon = enterIcon(imeAction, isMultiline)),
            ),
            gap,
        )
        val r3 = combineWithSplit(
            listOf(
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.927f, kind = KeyKind.FUNCTION),
                sym("–"), sym("—"), sym("↑"), sym("↓"),
            ),
            listOf(
                sym("←"), sym("→"), sym("✓"), sym("✗"), sym("★"),
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.364f, kind = KeyKind.FUNCTION),
            ),
            gap,
        )
        val r4 = if (split) {
            combineWithSplit(
                listOf(
                    KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.464f, kind = KeyKind.FUNCTION),
                    KeySpec("Space", KeyAction.Space, weight = 4.500f, kind = KeyKind.SPACE),
                ),
                listOf(
                    KeySpec("Space", KeyAction.Space, weight = 4.500f, kind = KeyKind.SPACE),
                    KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.464f, kind = KeyKind.FUNCTION),
                ),
                gap,
            )
        } else {
            listOf(
                KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.464f, kind = KeyKind.FUNCTION),
                KeySpec("?123", KeyAction.SwitchToSymbols1, weight = 1.464f, kind = KeyKind.FUNCTION),
                KeySpec("Space", KeyAction.Space, weight = 7.527f, kind = KeyKind.SPACE),
                KeySpec("◀", KeyAction.CursorLeft, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_LEFT),
                KeySpec("▶", KeyAction.CursorRight, weight = 0.873f, kind = KeyKind.FUNCTION, icon = KeyIcon.ARROW_RIGHT),
                KeySpec("ABC", KeyAction.SwitchToAlpha, weight = 1.000f, kind = KeyKind.FUNCTION),
            )
        }
        return listOf(r0, r1, r2, r3, r4)
    }

    private fun buildNumpadRows(imeAction: Int, isMultiline: Boolean): List<List<KeySpec>> {
        fun num(s: String) = KeySpec(s, KeyAction.Text(s, isNumpad = true), weight = 1.4f)
        fun fn(s: String) = KeySpec(s, KeyAction.Text(s, isNumpad = true), weight = 1.1f, kind = KeyKind.FUNCTION)
        return listOf(
            listOf(spacer(1.5f), num("1"), num("2"), num("3"), fn("-"), spacer(1.5f)),
            listOf(spacer(1.5f), num("4"), num("5"), num("6"), fn("+"), spacer(1.5f)),
            listOf(
                spacer(1.5f), num("7"), num("8"), num("9"),
                KeySpec("⌫", KeyAction.Backspace, weight = 1.1f, kind = KeyKind.FUNCTION, icon = KeyIcon.BACKSPACE),
                spacer(1.5f),
            ),
            listOf(
                spacer(1.5f),
                fn("*"),
                num("0"),
                fn("."),
                KeySpec("↵", KeyAction.Enter, weight = 1.1f, kind = KeyKind.ACTION, icon = enterIcon(imeAction, isMultiline)),
                spacer(1.5f),
            ),
        )
    }

    private fun combineWithSplit(left: List<KeySpec>, right: List<KeySpec>, gap: Float): List<KeySpec> =
        if (gap > 0f) left + spacer(gap) + right else left + right

    private fun spacer(weight: Float) = KeySpec("", KeyAction.None, weight = weight, kind = KeyKind.SPACER)

    private fun enterIcon(imeAction: Int, isMultiline: Boolean): KeyIcon {
        if (isMultiline) return KeyIcon.ENTER
        return when (imeAction) {
            EditorInfo.IME_ACTION_SEARCH -> KeyIcon.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyIcon.SEND
            EditorInfo.IME_ACTION_GO -> KeyIcon.GO
            EditorInfo.IME_ACTION_NEXT -> KeyIcon.NEXT
            EditorInfo.IME_ACTION_DONE -> KeyIcon.DONE
            else -> KeyIcon.ENTER
        }
    }
}
