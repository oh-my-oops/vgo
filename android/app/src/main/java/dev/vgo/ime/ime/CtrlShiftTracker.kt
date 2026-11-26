package dev.vgo.ime.ime

/**
 * Pure Ctrl+Shift chord detector (matches fcitx5/Linux behavior).
 * Triggers on key-up ONLY when Ctrl and Shift were held together without any other key pressed.
 * Never consumes Ctrl/Shift events so Ctrl+Shift+C / Ctrl+Shift+Tab / Ctrl+Shift+Arrow pass through.
 */
class CtrlShiftTracker {
    private var leftCtrlDown = false
    private var rightCtrlDown = false
    private var leftShiftDown = false
    private var rightShiftDown = false
    private var otherKeyPressed = false
    private var armed = false

    private val anyCtrlDown: Boolean get() = leftCtrlDown || rightCtrlDown
    private val anyShiftDown: Boolean get() = leftShiftDown || rightShiftDown

    fun onKeyDown(keyCode: Int, altPressed: Boolean, metaPressed: Boolean) {
        if (altPressed || metaPressed) {
            if (anyCtrlDown || anyShiftDown) otherKeyPressed = true
            armed = false
            return
        }
        val wasIdle = !anyCtrlDown && !anyShiftDown
        when (keyCode) {
            KEYCODE_CTRL_LEFT -> {
                if (wasIdle) otherKeyPressed = false
                leftCtrlDown = true
                if (anyShiftDown && !otherKeyPressed) armed = true
            }
            KEYCODE_CTRL_RIGHT -> {
                if (wasIdle) otherKeyPressed = false
                rightCtrlDown = true
                if (anyShiftDown && !otherKeyPressed) armed = true
            }
            KEYCODE_SHIFT_LEFT -> {
                if (wasIdle) otherKeyPressed = false
                leftShiftDown = true
                if (anyCtrlDown && !otherKeyPressed) armed = true
            }
            KEYCODE_SHIFT_RIGHT -> {
                if (wasIdle) otherKeyPressed = false
                rightShiftDown = true
                if (anyCtrlDown && !otherKeyPressed) armed = true
            }
            else -> {
                if (anyCtrlDown || anyShiftDown) {
                    otherKeyPressed = true
                }
                armed = false
            }
        }
    }

    /** Returns true once when a clean Ctrl+Shift chord completes on release. */
    fun onKeyUp(keyCode: Int): Boolean {
        val isCtrlOrShift = when (keyCode) {
            KEYCODE_CTRL_LEFT -> {
                leftCtrlDown = false; true
            }
            KEYCODE_CTRL_RIGHT -> {
                rightCtrlDown = false; true
            }
            KEYCODE_SHIFT_LEFT -> {
                leftShiftDown = false; true
            }
            KEYCODE_SHIFT_RIGHT -> {
                rightShiftDown = false; true
            }
            else -> false
        }

        if (!isCtrlOrShift) return false

        val triggered = armed && !otherKeyPressed
        if (triggered) {
            armed = false
        }
        if (!anyCtrlDown && !anyShiftDown) {
            otherKeyPressed = false
            armed = false
        }
        return triggered
    }

    fun reset() {
        leftCtrlDown = false
        rightCtrlDown = false
        leftShiftDown = false
        rightShiftDown = false
        otherKeyPressed = false
        armed = false
    }

    companion object {
        // Mirror android.view.KeyEvent constants so this class stays JVM-testable without mocks.
        const val KEYCODE_SHIFT_LEFT = 59
        const val KEYCODE_SHIFT_RIGHT = 60
        const val KEYCODE_CTRL_LEFT = 113
        const val KEYCODE_CTRL_RIGHT = 114
    }
}
