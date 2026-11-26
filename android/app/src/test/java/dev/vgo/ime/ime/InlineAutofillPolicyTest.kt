package dev.vgo.ime.ime

import dev.vgo.ime.ui.ActiveAuxPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAutofillPolicyTest {

    @Test
    fun autofillPrioritizesOverClipboardAndWorksOnPasswordFields() {
        val normalPolicy = EditorPolicy(packageName = "com.example.browser", bypassVietnamese = false, isPassword = false)
        val passwordPolicy = EditorPolicy(packageName = "com.example.browser", bypassVietnamese = true, isPassword = true)

        // Autofill beats clipboard on normal field
        assertEquals(
            ToolbarCenterMode.INLINE_AUTOFILL,
            InlineAutofillState.resolveToolbarCenterMode(
                hasInlineAutofill = true,
                activeAuxPanel = ActiveAuxPanel.NONE,
                composingNonEmpty = false,
                vnEnabled = true,
                policy = normalPolicy,
                hasClipboardSuggestion = true,
            ),
        )

        // Autofill STILL shows on password fields (while VN composing & clipboard stay blocked)
        assertEquals(
            ToolbarCenterMode.INLINE_AUTOFILL,
            InlineAutofillState.resolveToolbarCenterMode(
                hasInlineAutofill = true,
                activeAuxPanel = ActiveAuxPanel.NONE,
                composingNonEmpty = false,
                vnEnabled = true,
                policy = passwordPolicy,
                hasClipboardSuggestion = true,
            ),
        )

        // Once autofill clears on password field -> EMPTY (clipboard never shown on passwords!)
        assertEquals(
            ToolbarCenterMode.EMPTY,
            InlineAutofillState.resolveToolbarCenterMode(
                hasInlineAutofill = false,
                activeAuxPanel = ActiveAuxPanel.NONE,
                composingNonEmpty = true,
                vnEnabled = true,
                policy = passwordPolicy,
                hasClipboardSuggestion = true,
            ),
        )

        // On normal field without autofill: composing beats clipboard; idle shows clipboard
        assertEquals(
            ToolbarCenterMode.COMPOSING_FLIP,
            InlineAutofillState.resolveToolbarCenterMode(
                hasInlineAutofill = false,
                activeAuxPanel = ActiveAuxPanel.NONE,
                composingNonEmpty = true,
                vnEnabled = true,
                policy = normalPolicy,
                hasClipboardSuggestion = true,
            ),
        )
        assertEquals(
            ToolbarCenterMode.CLIPBOARD_CHIP,
            InlineAutofillState.resolveToolbarCenterMode(
                hasInlineAutofill = false,
                activeAuxPanel = ActiveAuxPanel.NONE,
                composingNonEmpty = false,
                vnEnabled = true,
                policy = normalPolicy,
                hasClipboardSuggestion = true,
            ),
        )
    }

    @Test
    fun epochGuardRejectsStaleInflationsAfterFieldChangeOrHide() {
        val state = InlineAutofillState()
        val epoch1 = state.nextEpoch()
        assertTrue(state.acceptInflatedResult(epoch1, hasAnyView = true))
        assertTrue(state.active)

        // Field switches / keyboard hides -> clears immediately
        state.clear()
        assertFalse(state.active)

        // Late async inflate from previous field MUST be rejected
        assertFalse(state.acceptInflatedResult(epoch1, hasAnyView = true))
        assertFalse(state.active)
    }

    @Test
    fun partitionPinnedSeparatesRightPinnedVaultIconFromScrollableList() {
        data class FakeSug(val label: String, val pinned: Boolean)
        val input = listOf(
            FakeSug("user1@example.com", pinned = false),
            FakeSug("user2@example.com", pinned = false),
            FakeSug("Bitwarden Vault", pinned = true),
        )
        val (regular, pinned) = InlineAutofillState.partitionPinned(input) { it.pinned }
        assertEquals(listOf("user1@example.com", "user2@example.com"), regular.map { it.label })
        assertEquals("Bitwarden Vault", pinned?.label)
    }
}
