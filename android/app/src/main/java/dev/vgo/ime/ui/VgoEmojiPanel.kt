package dev.vgo.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class VgoEmojiPanel(
    context: Context,
    private val onEmojiSelected: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private data class Category(val icon: String, val emojis: List<String>)

    private val density = resources.displayMetrics.density
    private val prefs = context.applicationContext.getSharedPreferences("vgo_emoji_recents", Context.MODE_PRIVATE)
    private var palette = KeyboardTheme.resolve(context)
    private var selectedCategory = 1 // Start on Smileys if recents empty

    private val baseCategories = listOf(
        Category("🕒", emptyList()),
        Category(
            "😀",
            listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😉",
                "😊", "😇", "🥰", "😍", "🤩", "😘", "😋", "😜", "🤪", "🤨",
                "🧐", "🤓", "😎", "🥳", "😏", "😒", "😞", "😔", "😟", "😕",
                "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠",
                "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😓",
                "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄",
                "😯", "😦", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐",
            )
        ),
        Category(
            "👍",
            listOf(
                "👍", "👎", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙",
                "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋",
                "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️", "💪", "👀", "❤️",
                "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❤️‍🔥", "💯",
                "💢", "💥", "💫", "💦", "💨", "💬", "💭", "🎉", "🎊", "✨",
            )
        ),
        Category(
            "💻",
            listOf(
                "💻", "🖥️", "⌨️", "🖱️", "📱", "⌚", "📷", "🎧", "🔌", "🔋",
                "💡", "🔦", "📌", "📍", "📎", "📝", "📁", "📂", "📅", "📆",
                "📈", "📉", "📊", "📋", "🔍", "🔎", "🔒", "🔓", "🔑", "🛠️",
                "⚙️", "🔧", "🔨", "🧪", "🧬", "📦", "📧", "📩", "🚀", "🛸",
                "✅", "❌", "⚠️", "ℹ️", "🔥", "⭐", "⚡", "🎯", "🏆", "🔔",
            )
        ),
        Category(
            "☕",
            listOf(
                "☕", "🍵", "🧋", "🍺", "🍻", "🥂", "🍷", "🥤", "🍜", "🍲",
                "🍛", "🍚", "🍙", "🍞", "🥐", "🥖", "🍔", "🍟", "🍕", "🌭",
                "🥪", "🌮", "🌯", "🥗", "🍗", "🍖", "🍤", "🍰", "🎂", "🍪",
                "🍫", "🍎", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🥭", "🍍",
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐸", "🦆",
            )
        ),
        Category(
            "🇻🇳",
            listOf(
                "🇻🇳", "🇬🇧", "🇺🇸", "🇯🇵", "🇰🇷", "🇸🇬", "🇫🇷", "🇩🇪", "🇦🇺", "🇨🇦",
                "🌍", "🌏", "🗺️", "🧭", "🏔️", "🏖️", "🏝️", "🏙️", "🏠", "🏢",
                "🚗", "🚕", "🚌", "🚇", "✈️", "🛵", "🚲", "⛵", "☀️", "🌤️",
                "⛅", "🌧️", "⛈️", "🌈", "❄️", "🌙", "⭐", "🌊", "🌸", "🌿",
            )
        ),
    )

    private val tabBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private val backBtn = createBarButton("ABC") { onClose() }
    private val backspaceBtn = createBarButton("⌫") { onBackspace() }
    private val tabViews = mutableListOf<TextView>()

    private val scrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
    }
    private val grid = GridLayout(context).apply {
        setPadding(dp(8), dp(4), dp(8), dp(8))
    }

    init {
        orientation = VERTICAL
        tabBar.addView(backBtn, marginEnd(dp(6)))
        baseCategories.forEachIndexed { idx, cat ->
            val tv = TextView(context).apply {
                text = cat.icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                minHeight = dp(34)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedCategory = idx
                    render(palette)
                }
            }
            tabViews += tv
            tabBar.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        tabBar.addView(backspaceBtn, marginStart(dp(6)))

        addView(tabBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scrollView.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, dp(220)))

        if (loadRecents().isNotEmpty()) {
            selectedCategory = 0
        }
        render(palette)
    }

    fun render(newPalette: KeyboardPalette) {
        palette = newPalette
        setBackgroundColor(palette.background)
        styleBarButton(backBtn)
        styleBarButton(backspaceBtn)

        val panelHeightDp = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            215
        } else {
            255
        }
        scrollView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(panelHeightDp))

        tabViews.forEachIndexed { idx, tv ->
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (idx == selectedCategory) palette.chipActiveBg else android.graphics.Color.TRANSPARENT)
            }
        }

        val list = if (selectedCategory == 0) {
            loadRecents().ifEmpty { baseCategories[1].emojis }
        } else {
            baseCategories[selectedCategory].emojis
        }

        grid.removeAllViews()
        val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 12 else 8
        grid.columnCount = columns

        list.forEachIndexed { idx, emoji ->
            val cell = TextView(context).apply {
                text = emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                gravity = Gravity.CENTER
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    recordRecent(emoji)
                    onEmojiSelected(emoji)
                }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(idx / columns),
                GridLayout.spec(idx % columns, 1f),
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            grid.addView(cell, lp)
        }
    }

    private fun loadRecents(): List<String> {
        val raw = prefs.getString(KEY_RECENTS, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\u001F').filter { it.isNotEmpty() }
    }

    private fun recordRecent(emoji: String) {
        val cur = loadRecents().toMutableList()
        cur.remove(emoji)
        cur.add(0, emoji)
        val trimmed = cur.take(32)
        prefs.edit().putString(KEY_RECENTS, trimmed.joinToString("\u001F")).apply()
    }

    private fun createBarButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun styleBarButton(view: TextView) {
        view.setTextColor(palette.keyText)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(palette.funcKeyBg)
        }
    }

    private fun marginEnd(px: Int) =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = px }

    private fun marginStart(px: Int) =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        private const val KEY_RECENTS = "recents"
    }
}
