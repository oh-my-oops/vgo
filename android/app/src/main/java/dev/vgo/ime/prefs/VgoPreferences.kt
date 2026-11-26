package dev.vgo.ime.prefs

import android.content.Context
import android.content.SharedPreferences
import dev.vgo.ime.engine.InputMethodMode

enum class InputSource(val label: String) {
    SOFT("Cảm ứng"),
    HARDWARE("Phím cứng"),
}

enum class HwToggleShortcut(val prefValue: String, val label: String) {
    CTRL_SHIFT("ctrl_shift", "Ctrl + Shift"),
    SHIFT_SPACE("shift_space", "Shift + Space"),
    CTRL_SPACE("ctrl_space", "Ctrl + Space"),
    NONE("none", "Tắt");

    companion object {
        fun fromPref(value: String?): HwToggleShortcut =
            entries.firstOrNull { it.prefValue == value } ?: CTRL_SHIFT
    }
}

enum class ThemeMode(val prefValue: String, val label: String) {
    SYSTEM("system", "Theo hệ thống"),
    DARK("dark", "Tối"),
    LIGHT("light", "Sáng");

    fun next(): ThemeMode = when (this) {
        SYSTEM -> DARK
        DARK -> LIGHT
        LIGHT -> SYSTEM
    }

    companion object {
        fun fromPref(value: String?): ThemeMode =
            entries.firstOrNull { it.prefValue == value } ?: SYSTEM
    }
}

data class VgoSettings(
    val softMethod: InputMethodMode = InputMethodMode.TELEX,
    val hardwareMethod: InputMethodMode = InputMethodMode.VNI,
    val vnEnabled: Boolean = true,
    val oldStyleTone: Boolean = true,
    val wAlwaysU: Boolean = false,
    val noUnderline: Boolean = true,
    val composingFallbackApps: Set<String> = emptySet(),
    val hwToggleShortcut: HwToggleShortcut = HwToggleShortcut.CTRL_SHIFT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val clipboardEnabled: Boolean = true,
    val showNumberRow: Boolean = true,
    val showVniHints: Boolean = false,
    val splitLayout: Boolean = false,
    val portraitHeightPct: Int = 100,
    val landscapeHeightPct: Int = 100,
    val hapticEnabled: Boolean = true,
    val swipeSpaceCursor: Boolean = true,
    val autoCap: Boolean = true,
    val doubleSpacePeriod: Boolean = false,
    val suggestionsEnabled: Boolean = true,
    /** true: fit as many suggestion slots as the screen width allows; false: 3 like Gboard. */
    val autoSuggestionCount: Boolean = true,
) {
    fun methodFor(source: InputSource): InputMethodMode = when (source) {
        InputSource.SOFT -> softMethod
        InputSource.HARDWARE -> hardwareMethod
    }
}

class VgoPreferences(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): VgoSettings = VgoSettings(
        softMethod = InputMethodMode.fromPref(
            prefs.getString(KEY_SOFT_METHOD, InputMethodMode.TELEX.prefValue)
        ),
        hardwareMethod = InputMethodMode.fromPref(
            prefs.getString(KEY_HARDWARE_METHOD, InputMethodMode.VNI.prefValue)
        ),
        vnEnabled = prefs.getBoolean(KEY_VN_ENABLED, true),
        oldStyleTone = prefs.getBoolean(KEY_OLD_STYLE_TONE, true),
        wAlwaysU = prefs.getBoolean(KEY_W_ALWAYS_U, false),
        noUnderline = prefs.getBoolean(KEY_NO_UNDERLINE, true),
        composingFallbackApps = prefs.getStringSet(KEY_COMPOSING_FALLBACK_APPS, emptySet())
            ?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        hwToggleShortcut = HwToggleShortcut.fromPref(
            prefs.getString(KEY_HW_TOGGLE_SHORTCUT, HwToggleShortcut.CTRL_SHIFT.prefValue)
        ),
        themeMode = ThemeMode.fromPref(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.prefValue)),
        clipboardEnabled = prefs.getBoolean(KEY_CLIPBOARD_ENABLED, true),
        showNumberRow = prefs.getBoolean(KEY_SHOW_NUMBER_ROW, true),
        showVniHints = prefs.getBoolean(KEY_SHOW_VNI_HINTS, false),
        splitLayout = prefs.getBoolean(KEY_SPLIT_LAYOUT, false),
        portraitHeightPct = prefs.getInt(KEY_PORTRAIT_HEIGHT_PCT, 100).coerceIn(75, 130),
        landscapeHeightPct = prefs.getInt(KEY_LANDSCAPE_HEIGHT_PCT, 100).coerceIn(75, 130),
        hapticEnabled = prefs.getBoolean(KEY_HAPTIC_ENABLED, true),
        swipeSpaceCursor = prefs.getBoolean(KEY_SWIPE_SPACE_CURSOR, true),
        autoCap = prefs.getBoolean(KEY_AUTO_CAP, true),
        doubleSpacePeriod = prefs.getBoolean(KEY_DOUBLE_SPACE_PERIOD, false),
        suggestionsEnabled = prefs.getBoolean(KEY_SUGGESTIONS_ENABLED, true),
        autoSuggestionCount = prefs.getBoolean(KEY_AUTO_SUGGESTION_COUNT, true),
    )

    fun setSoftMethod(mode: InputMethodMode) {
        prefs.edit().putString(KEY_SOFT_METHOD, mode.prefValue).apply()
    }

    fun setHardwareMethod(mode: InputMethodMode) {
        prefs.edit().putString(KEY_HARDWARE_METHOD, mode.prefValue).apply()
    }

    fun setMethodFor(source: InputSource, mode: InputMethodMode) {
        when (source) {
            InputSource.SOFT -> setSoftMethod(mode)
            InputSource.HARDWARE -> setHardwareMethod(mode)
        }
    }

    fun setVnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VN_ENABLED, enabled).apply()
    }

    fun setOldStyleTone(oldStyle: Boolean) {
        prefs.edit().putBoolean(KEY_OLD_STYLE_TONE, oldStyle).apply()
    }

    fun setWAlwaysU(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_W_ALWAYS_U, enabled).apply()
    }

    fun setNoUnderline(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NO_UNDERLINE, enabled).apply()
    }

    fun addComposingFallbackApp(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val next = read().composingFallbackApps + pkg
        prefs.edit().putStringSet(KEY_COMPOSING_FALLBACK_APPS, next).apply()
    }

    fun removeComposingFallbackApp(packageName: String) {
        val next = read().composingFallbackApps - packageName
        prefs.edit().putStringSet(KEY_COMPOSING_FALLBACK_APPS, next).apply()
    }

    fun setHwToggleShortcut(shortcut: HwToggleShortcut) {
        prefs.edit().putString(KEY_HW_TOGGLE_SHORTCUT, shortcut.prefValue).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.prefValue).apply()
    }

    fun setClipboardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLIPBOARD_ENABLED, enabled).apply()
    }

    fun setShowNumberRow(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_NUMBER_ROW, enabled).apply()
    }

    fun setShowVniHints(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_VNI_HINTS, enabled).apply()
    }

    fun setSplitLayout(split: Boolean) {
        prefs.edit().putBoolean(KEY_SPLIT_LAYOUT, split).apply()
    }

    fun setPortraitHeightPct(pct: Int) {
        prefs.edit().putInt(KEY_PORTRAIT_HEIGHT_PCT, pct.coerceIn(75, 130)).apply()
    }

    fun setLandscapeHeightPct(pct: Int) {
        prefs.edit().putInt(KEY_LANDSCAPE_HEIGHT_PCT, pct.coerceIn(75, 130)).apply()
    }

    fun setHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun setSwipeSpaceCursor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SWIPE_SPACE_CURSOR, enabled).apply()
    }

    fun setAutoCap(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CAP, enabled).apply()
    }

    fun setDoubleSpacePeriod(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOUBLE_SPACE_PERIOD, enabled).apply()
    }

    fun setSuggestionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUGGESTIONS_ENABLED, enabled).apply()
    }

    fun setAutoSuggestionCount(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SUGGESTION_COUNT, enabled).apply()
    }

    fun learnedRevision(): Long = prefs.getLong(KEY_LEARNED_REVISION, 0L)

    fun bumpLearnedRevision() {
        prefs.edit().putLong(KEY_LEARNED_REVISION, learnedRevision() + 1L).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "vgo_ime_prefs"
        private const val KEY_SOFT_METHOD = "soft_method"
        private const val KEY_HARDWARE_METHOD = "hardware_method"
        private const val KEY_VN_ENABLED = "vn_enabled"
        private const val KEY_OLD_STYLE_TONE = "old_style_tone"
        private const val KEY_W_ALWAYS_U = "w_always_u"
        private const val KEY_NO_UNDERLINE = "no_underline"
        private const val KEY_COMPOSING_FALLBACK_APPS = "composing_fallback_apps"
        private const val KEY_HW_TOGGLE_SHORTCUT = "hw_toggle_shortcut"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
        private const val KEY_SHOW_NUMBER_ROW = "show_number_row"
        private const val KEY_SHOW_VNI_HINTS = "show_vni_hints"
        private const val KEY_SPLIT_LAYOUT = "split_layout"
        private const val KEY_PORTRAIT_HEIGHT_PCT = "portrait_height_pct"
        private const val KEY_LANDSCAPE_HEIGHT_PCT = "landscape_height_pct"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_SWIPE_SPACE_CURSOR = "swipe_space_cursor"
        private const val KEY_AUTO_CAP = "auto_cap"
        private const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period"
        private const val KEY_LEARNED_REVISION = "learned_revision"
        private const val KEY_SUGGESTIONS_ENABLED = "suggestions_enabled"
        private const val KEY_AUTO_SUGGESTION_COUNT = "auto_suggestion_count"
    }
}
