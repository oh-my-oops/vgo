package dev.vgo.ime.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SuggestionSlotOrderTest {
    @Test
    fun bestIsCenteredThenAlternatesLeftRight() {
        assertArrayEquals(intArrayOf(1, 0, 2), VgoToolbarView.rankOrder(3))
        assertArrayEquals(intArrayOf(1, 0, 2, 3), VgoToolbarView.rankOrder(4))
        assertArrayEquals(intArrayOf(2, 1, 3, 0, 4), VgoToolbarView.rankOrder(5))
        assertArrayEquals(intArrayOf(3, 2, 4, 1, 5, 0, 6), VgoToolbarView.rankOrder(7))
    }
}
