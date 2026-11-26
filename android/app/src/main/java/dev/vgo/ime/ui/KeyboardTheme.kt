package dev.vgo.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils
import dev.vgo.ime.prefs.ThemeMode

data class KeyboardPalette(
    val isDark: Boolean,
    val background: Int,
    val toolbarBackground: Int,
    val keyBg: Int,
    val keyPressedBg: Int,
    val keyShadow: Int,
    val funcKeyBg: Int,
    val funcKeyPressedBg: Int,
    val actionKeyBg: Int,
    val actionKeyPressedBg: Int,
    val actionKeyText: Int,
    val keyText: Int,
    val funcKeyText: Int,
    val keyHintText: Int,
    val chipBg: Int,
    val chipActiveBg: Int,
    val chipActiveText: Int,
    val chipFlipBg: Int,
    val chipFlipText: Int,
    val popupBg: Int,
    val popupSelectedBg: Int,
    val popupText: Int,
    val divider: Int,
)

object KeyboardTheme {
    fun resolve(context: Context, mode: ThemeMode = ThemeMode.SYSTEM): KeyboardPalette {
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return if (dark) {
                val n900 = context.getColor(android.R.color.system_neutral1_900)
                val n800 = context.getColor(android.R.color.system_neutral1_800)
                val n50 = context.getColor(android.R.color.system_neutral1_50)
                val a1_900 = context.getColor(android.R.color.system_accent1_900)
                val a1_700 = context.getColor(android.R.color.system_accent1_700)
                val a1_600 = context.getColor(android.R.color.system_accent1_600)
                val a2_800 = context.getColor(android.R.color.system_accent2_800)
                val a2_700 = context.getColor(android.R.color.system_accent2_700)
                val a2_200 = context.getColor(android.R.color.system_accent2_200)

                // Calibrated directly against Gboard 18.6 dark Material You on TB-J716F:
                // bg=#1B191F, keyBg=#2E2B34, funcKeyBg & enter=#3E384C, divider=#4A4650.
                val bg = ColorUtils.blendARGB(n900, a1_900, 0.14f)
                val keyBg = ColorUtils.blendARGB(n800, a2_800, 0.22f)
                val funcBg = ColorUtils.blendARGB(a2_800, a1_700, 0.42f)
                val actionBg = funcBg
                val activeBg = ColorUtils.blendARGB(a2_700, a1_600, 0.50f)

                KeyboardPalette(
                    isDark = true,
                    background = bg,
                    toolbarBackground = bg,
                    keyBg = keyBg,
                    keyPressedBg = ColorUtils.blendARGB(keyBg, Color.WHITE, 0.14f),
                    keyShadow = Color.TRANSPARENT,
                    funcKeyBg = funcBg,
                    funcKeyPressedBg = ColorUtils.blendARGB(funcBg, Color.WHITE, 0.14f),
                    actionKeyBg = actionBg,
                    actionKeyPressedBg = ColorUtils.blendARGB(actionBg, Color.WHITE, 0.16f),
                    actionKeyText = ColorUtils.blendARGB(n50, a2_200, 0.15f),
                    keyText = n50,
                    funcKeyText = ColorUtils.blendARGB(n50, a2_200, 0.20f),
                    keyHintText = context.getColor(android.R.color.system_neutral2_300),
                    chipBg = keyBg,
                    chipActiveBg = activeBg,
                    chipActiveText = Color.WHITE,
                    chipFlipBg = funcBg,
                    chipFlipText = n50,
                    popupBg = ColorUtils.blendARGB(keyBg, a1_700, 0.35f),
                    popupSelectedBg = activeBg,
                    popupText = Color.WHITE,
                    divider = ColorUtils.blendARGB(keyBg, Color.WHITE, 0.18f),
                )
            } else {
                val n100 = context.getColor(android.R.color.system_neutral2_100)
                val n200 = context.getColor(android.R.color.system_neutral2_200)
                val n900 = context.getColor(android.R.color.system_neutral1_900)
                val a1_100 = context.getColor(android.R.color.system_accent1_100)
                val a1_200 = context.getColor(android.R.color.system_accent1_200)
                val a1_600 = context.getColor(android.R.color.system_accent1_600)
                val a2_200 = context.getColor(android.R.color.system_accent2_200)

                val bg = ColorUtils.blendARGB(n100, a1_100, 0.18f)
                val funcBg = ColorUtils.blendARGB(n200, a2_200, 0.50f)
                KeyboardPalette(
                    isDark = false,
                    background = bg,
                    toolbarBackground = bg,
                    keyBg = Color.WHITE,
                    keyPressedBg = a1_100,
                    keyShadow = Color.argb(24, 0, 0, 0),
                    funcKeyBg = funcBg,
                    funcKeyPressedBg = a1_200,
                    actionKeyBg = funcBg,
                    actionKeyPressedBg = a1_200,
                    actionKeyText = n900,
                    keyText = n900,
                    funcKeyText = n900,
                    keyHintText = context.getColor(android.R.color.system_neutral2_600),
                    chipBg = Color.WHITE,
                    chipActiveBg = a1_600,
                    chipActiveText = Color.WHITE,
                    chipFlipBg = a1_100,
                    chipFlipText = n900,
                    popupBg = Color.WHITE,
                    popupSelectedBg = a1_100,
                    popupText = n900,
                    divider = Color.argb(36, 0, 0, 0),
                )
            }
        }

        return if (dark) {
            KeyboardPalette(
                isDark = true,
                background = 0xFF1B191F.toInt(),
                toolbarBackground = 0xFF1B191F.toInt(),
                keyBg = 0xFF2E2B34.toInt(),
                keyPressedBg = 0xFF474156.toInt(),
                keyShadow = Color.TRANSPARENT,
                funcKeyBg = 0xFF3E384C.toInt(),
                funcKeyPressedBg = 0xFF534C66.toInt(),
                actionKeyBg = 0xFF3E384C.toInt(),
                actionKeyPressedBg = 0xFF534C66.toInt(),
                actionKeyText = 0xFFE8E2F0.toInt(),
                keyText = 0xFFF3EFF7.toInt(),
                funcKeyText = 0xFFE3DCEB.toInt(),
                keyHintText = 0xFFA49DB0.toInt(),
                chipBg = 0xFF2E2B34.toInt(),
                chipActiveBg = 0xFF564E69.toInt(),
                chipActiveText = Color.WHITE,
                chipFlipBg = 0xFF3E384C.toInt(),
                chipFlipText = 0xFFF3EFF7.toInt(),
                popupBg = 0xFF3E384C.toInt(),
                popupSelectedBg = 0xFF564E69.toInt(),
                popupText = Color.WHITE,
                divider = 0xFF4A4650.toInt(),
            )
        } else {
            KeyboardPalette(
                isDark = false,
                background = 0xFFECEAF0.toInt(),
                toolbarBackground = 0xFFECEAF0.toInt(),
                keyBg = Color.WHITE,
                keyPressedBg = 0xFFDAD5EC.toInt(),
                keyShadow = Color.argb(24, 0, 0, 0),
                funcKeyBg = 0xFFDBD7E6.toInt(),
                funcKeyPressedBg = 0xFFC8C2DA.toInt(),
                actionKeyBg = 0xFFDBD7E6.toInt(),
                actionKeyPressedBg = 0xFFC8C2DA.toInt(),
                actionKeyText = 0xFF1C1B1F.toInt(),
                keyText = 0xFF1C1B1F.toInt(),
                funcKeyText = 0xFF1C1B1F.toInt(),
                keyHintText = 0xFF5F5B68.toInt(),
                chipBg = Color.WHITE,
                chipActiveBg = 0xFF5E5378.toInt(),
                chipActiveText = Color.WHITE,
                chipFlipBg = 0xFFE4DDF5.toInt(),
                chipFlipText = 0xFF1C1B1F.toInt(),
                popupBg = Color.WHITE,
                popupSelectedBg = 0xFFDAD5EC.toInt(),
                popupText = 0xFF1C1B1F.toInt(),
                divider = Color.argb(36, 0, 0, 0),
            )
        }
    }
}
