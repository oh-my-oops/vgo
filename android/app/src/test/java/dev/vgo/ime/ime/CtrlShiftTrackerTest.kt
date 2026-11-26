package dev.vgo.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CtrlShiftTrackerTest {

    @Test
    fun ctrlThenShiftReleaseTriggersOnce() {
        val t = CtrlShiftTracker()
        t.onKeyDown(CtrlShiftTracker.KEYCODE_CTRL_LEFT, altPressed = false, metaPressed = false)
        t.onKeyDown(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, altPressed = false, metaPressed = false)
        assertTrue(t.onKeyUp(CtrlShiftTracker.KEYCODE_SHIFT_LEFT))
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_CTRL_LEFT))
    }

    @Test
    fun shiftThenCtrlReleaseTriggersOnce() {
        val t = CtrlShiftTracker()
        t.onKeyDown(CtrlShiftTracker.KEYCODE_SHIFT_RIGHT, altPressed = false, metaPressed = false)
        t.onKeyDown(CtrlShiftTracker.KEYCODE_CTRL_RIGHT, altPressed = false, metaPressed = false)
        assertTrue(t.onKeyUp(CtrlShiftTracker.KEYCODE_CTRL_RIGHT))
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_SHIFT_RIGHT))
    }

    @Test
    fun ctrlShiftPlusLetterDoesNotTrigger() {
        val t = CtrlShiftTracker()
        t.onKeyDown(CtrlShiftTracker.KEYCODE_CTRL_LEFT, altPressed = false, metaPressed = false)
        t.onKeyDown(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, altPressed = false, metaPressed = false)
        t.onKeyDown(31 /* KEYCODE_C */, altPressed = false, metaPressed = false)
        assertFalse(t.onKeyUp(31))
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_SHIFT_LEFT))
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_CTRL_LEFT))

        // Next clean chord works again.
        t.onKeyDown(CtrlShiftTracker.KEYCODE_CTRL_LEFT, altPressed = false, metaPressed = false)
        t.onKeyDown(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, altPressed = false, metaPressed = false)
        assertTrue(t.onKeyUp(CtrlShiftTracker.KEYCODE_CTRL_LEFT))
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_SHIFT_LEFT))
    }

    @Test
    fun ctrlShiftWithAltDoesNotTrigger() {
        val t = CtrlShiftTracker()
        t.onKeyDown(CtrlShiftTracker.KEYCODE_CTRL_LEFT, altPressed = true, metaPressed = false)
        t.onKeyDown(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, altPressed = true, metaPressed = false)
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_SHIFT_LEFT))
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_CTRL_LEFT))
    }

    @Test
    fun singleModifierDoesNotTrigger() {
        val t = CtrlShiftTracker()
        t.onKeyDown(CtrlShiftTracker.KEYCODE_CTRL_LEFT, altPressed = false, metaPressed = false)
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_CTRL_LEFT))
        t.onKeyDown(CtrlShiftTracker.KEYCODE_SHIFT_LEFT, altPressed = false, metaPressed = false)
        assertFalse(t.onKeyUp(CtrlShiftTracker.KEYCODE_SHIFT_LEFT))
    }
}
