package dev.vgo.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.vgo.ime.R
import dev.vgo.ime.ui.ActiveAuxPanel
import dev.vgo.ime.ui.KeyboardPalette

enum class ToolbarCenterMode {
    INLINE_AUTOFILL,
    COMPOSING_FLIP,
    CLIPBOARD_CHIP,
    EMPTY,
}

/**
 * Pure, JVM-testable coordinator for Android 11+ Inline Autofill state & toolbar priority.
 * Never touches or reads password values — only tracks suggestion slots & lifecycle epochs.
 */
class InlineAutofillState {
    var epoch: Long = 0L
        private set
    var active: Boolean = false
        private set

    fun nextEpoch(): Long {
        epoch += 1L
        active = false
        return epoch
    }

    fun clear(): Long {
        epoch += 1L
        active = false
        return epoch
    }

    fun acceptInflatedResult(forEpoch: Long, hasAnyView: Boolean): Boolean {
        if (forEpoch != epoch) return false
        active = hasAnyView
        return true
    }

    companion object {
        fun resolveToolbarCenterMode(
            hasInlineAutofill: Boolean,
            activeAuxPanel: ActiveAuxPanel,
            composingNonEmpty: Boolean,
            vnEnabled: Boolean,
            policy: EditorPolicy,
            hasClipboardSuggestion: Boolean,
        ): ToolbarCenterMode {
            // Priority 1: Inline Autofill (Bitwarden / Credential Manager) always wins — including on password fields!
            if (hasInlineAutofill && activeAuxPanel == ActiveAuxPanel.NONE) {
                return ToolbarCenterMode.INLINE_AUTOFILL
            }
            // Priority 2: Active Vietnamese word + Esc flip chip (never on password/bypassed fields).
            if (composingNonEmpty && vnEnabled && !policy.bypassVietnamese) {
                return ToolbarCenterMode.COMPOSING_FLIP
            }
            // Priority 3: Recent clipboard suggestion chip (never on password fields).
            if (hasClipboardSuggestion && !policy.isPassword && activeAuxPanel == ActiveAuxPanel.NONE) {
                return ToolbarCenterMode.CLIPBOARD_CHIP
            }
            return ToolbarCenterMode.EMPTY
        }

        fun <T> partitionPinned(items: List<T>, isPinned: (T) -> Boolean): Pair<List<T>, T?> {
            val regular = mutableListOf<T>()
            var pinned: T? = null
            for (item in items) {
                if (isPinned(item) && pinned == null) {
                    pinned = item
                } else {
                    regular += item
                }
            }
            return regular to pinned
        }
    }
}

object InlineAutofillHelper {
    const val MAX_SUGGESTIONS = 6

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("RestrictedApi")
    fun buildRequest(context: Context, palette: KeyboardPalette): InlineSuggestionsRequest {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val bgRes = if (palette.isDark) R.drawable.bg_inline_chip_dark else R.drawable.bg_inline_chip_light
        val bgIcon = Icon.createWithResource(context, bgRes)

        val chipStyle = ViewStyle.Builder()
            .setBackground(bgIcon)
            .setPadding(dp(10), dp(0), dp(10), dp(0))
            .build()

        val singleIconChipStyle = ViewStyle.Builder()
            .setBackground(bgIcon)
            .setPadding(dp(6), dp(0), dp(6), dp(0))
            .build()

        val titleStyle = TextViewStyle.Builder()
            .setTextColor(palette.keyText)
            .setTextSize(13.5f)
            .build()

        val subtitleStyle = TextViewStyle.Builder()
            .setTextColor(palette.keyHintText)
            .setTextSize(11.5f)
            .build()

        val iconStyle = ImageViewStyle.Builder()
            .setMaxWidth(dp(20))
            .setMaxHeight(dp(20))
            .build()

        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(chipStyle)
            .setSingleIconChipStyle(singleIconChipStyle)
            .setTitleStyle(titleStyle)
            .setSubtitleStyle(subtitleStyle)
            .setStartIconStyle(iconStyle)
            .setEndIconStyle(iconStyle)
            .build()

        val stylesBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()

        val regularSpec = InlinePresentationSpec.Builder(
            Size(dp(80), dp(34)),
            Size(dp(260), dp(40)),
        ).setStyle(stylesBundle).build()

        val pinnedSpec = InlinePresentationSpec.Builder(
            Size(dp(36), dp(34)),
            Size(dp(44), dp(40)),
        ).setStyle(stylesBundle).build()

        return InlineSuggestionsRequest.Builder(listOf(regularSpec, pinnedSpec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }
}
