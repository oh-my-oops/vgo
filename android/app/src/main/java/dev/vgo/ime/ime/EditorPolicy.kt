package dev.vgo.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

data class EditorPolicy(
    val packageName: String = "",
    val bypassVietnamese: Boolean = false,
    val isPassword: Boolean = false,
    val isNumericField: Boolean = false,
    val isMultiline: Boolean = false,
    val imeAction: Int = EditorInfo.IME_ACTION_NONE,
    val initialSelEnd: Int = -1,
    val autoCapSentences: Boolean = false,
    val autoCapWords: Boolean = false,
    val autoCapAll: Boolean = false,
    /** App asked for no suggestions (TYPE_TEXT_FLAG_NO_SUGGESTIONS) or the field bypasses Vietnamese. */
    val noSuggestions: Boolean = false,
    /** Incognito (IME_FLAG_NO_PERSONALIZED_LEARNING): never learn what is typed here. */
    val noLearning: Boolean = false,
) {
    companion object {
        fun fromEditorInfo(info: EditorInfo?): EditorPolicy {
            if (info == null) return EditorPolicy(bypassVietnamese = true)
            val inputType = info.inputType
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val flags = inputType and InputType.TYPE_MASK_FLAGS

            val isPassword = when (inputClass) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }

            val isNumeric = inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME

            val isUriOrEmail = inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )

            val isNullClass = inputClass == InputType.TYPE_NULL
            val bypass = isPassword || isNumeric || isUriOrEmail || isNullClass

            val isMultiline = inputClass == InputType.TYPE_CLASS_TEXT &&
                (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

            val imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION

            return EditorPolicy(
                packageName = info.packageName.orEmpty(),
                bypassVietnamese = bypass,
                isPassword = isPassword,
                isNumericField = isNumeric,
                isMultiline = isMultiline,
                imeAction = imeAction,
                initialSelEnd = info.initialSelEnd,
                autoCapSentences = !bypass && (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0,
                autoCapWords = !bypass && (flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0,
                autoCapAll = !bypass && (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0,
                noSuggestions = bypass || (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0,
                noLearning = bypass || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
            )
        }
    }
}
