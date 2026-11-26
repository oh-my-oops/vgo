package dev.vgo.ime.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.vgo.ime.engine.AssetDataInstaller
import dev.vgo.ime.engine.InputMethodMode
import dev.vgo.ime.engine.LearnedCategory
import dev.vgo.ime.engine.LearnedItem
import dev.vgo.ime.engine.VgoLearnedStore
import dev.vgo.ime.engine.VgoNative
import dev.vgo.ime.prefs.HwToggleShortcut
import dev.vgo.ime.prefs.ThemeMode
import dev.vgo.ime.prefs.VgoPreferences
import dev.vgo.ime.prefs.VgoSettings
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = VgoPreferences(this)
        val dirs = AssetDataInstaller.ensureInstalled(this)
        val learnedStore = VgoLearnedStore(dirs.userDir)

        setContent {
            VgoTheme {
                VgoSettingsApp(
                    preferences = preferences,
                    learnedStore = learnedStore,
                    onOpenImeSettings = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onShowImePicker = {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                )
            }
        }
    }
}

@Composable
fun VgoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun VgoSettingsApp(
    preferences: VgoPreferences,
    learnedStore: VgoLearnedStore,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(preferences.read()) }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(
                        text = "vgo Keyboard",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Lõi C++ vgo · Cảm ứng: Telex · Phím cứng: VNI · Tự ẩn khi gắn phím ngoài",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Cài đặt") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Dữ liệu tự học") },
                    )
                }

                if (selectedTab == 0) {
                    MainSettingsTab(
                        settings = settings,
                        preferences = preferences,
                        onSettingsChanged = { settings = preferences.read() },
                        onOpenImeSettings = onOpenImeSettings,
                        onShowImePicker = onShowImePicker,
                    )
                } else {
                    LearnedDataTab(
                        learnedStore = learnedStore,
                        preferences = preferences,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainSettingsTab(
    settings: VgoSettings,
    preferences: VgoPreferences,
    onSettingsChanged: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
) {
    var testText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Activation section
        SectionCard(title = "Kích hoạt bộ gõ") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenImeSettings) {
                    Text("1. Bật trong Cài đặt")
                }
                OutlinedButton(onClick = onShowImePicker) {
                    Text("2. Chọn bộ gõ")
                }
            }
            Text(
                text = "Lưu ý: Chỉ cần bật thêm vgo, giữ nguyên Gboard / Laban Key bật sẵn để chuyển nhanh qua lại.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Core vgo engine section
        SectionCard(title = "Kiểu gõ & Lõi vgo") {
            Text("Bàn phím cảm ứng (mặc định Telex)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(InputMethodMode.TELEX, InputMethodMode.VNI).forEach { mode ->
                    FilterChip(
                        selected = settings.softMethod == mode,
                        onClick = {
                            preferences.setSoftMethod(mode)
                            onSettingsChanged()
                        },
                        label = { Text(mode.shortLabel) },
                    )
                }
            }

            Text("Bàn phím cứng — K380 / cover (mặc định VNI)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(InputMethodMode.VNI, InputMethodMode.TELEX).forEach { mode ->
                    FilterChip(
                        selected = settings.hardwareMethod == mode,
                        onClick = {
                            preferences.setHardwareMethod(mode)
                            onSettingsChanged()
                        },
                        label = { Text(mode.shortLabel) },
                    )
                }
            }

            SwitchRow(
                title = "Bật gõ tiếng Việt",
                subtitle = "Tắt để gõ tiếng Anh thuần (bàn phím cứng: ${settings.hwToggleShortcut.label})",
                checked = settings.vnEnabled,
                onCheckedChange = {
                    preferences.setVnEnabled(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Đặt dấu kiểu cũ (hòa, thúy)",
                subtitle = "Tắt để dùng kiểu mới (hoà, thuý)",
                checked = settings.oldStyleTone,
                onCheckedChange = {
                    preferences.setOldStyleTone(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Phím w đứng một mình thành ư (Telex)",
                subtitle = "Mặc định tắt để giữ chữ w khi gõ tiếng Anh",
                checked = settings.wAlwaysU,
                onCheckedChange = {
                    preferences.setWAlwaysU(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Gõ không gạch chân",
                subtitle = "Ghi đè theo diff giống vgo Linux (bật mặc định; có thể thêm app ngoại lệ bên dưới)",
                checked = settings.noUnderline,
                onCheckedChange = {
                    preferences.setNoUnderline(it)
                    onSettingsChanged()
                },
            )
            if (settings.noUnderline) {
                var newPkg by remember { mutableStateOf("") }
                Text(
                    text = "App ngoại lệ (quay về gạch chân nếu xóa lỗi)",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newPkg,
                        onValueChange = { newPkg = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("vd: com.example.app") },
                    )
                    OutlinedButton(
                        onClick = {
                            if (newPkg.isNotBlank()) {
                                preferences.addComposingFallbackApp(newPkg)
                                newPkg = ""
                                onSettingsChanged()
                            }
                        }
                    ) { Text("Thêm") }
                }
                if (settings.composingFallbackApps.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        settings.composingFallbackApps.sorted().forEach { pkg ->
                            FilterChip(
                                selected = true,
                                onClick = {
                                    preferences.removeComposingFallbackApp(pkg)
                                    onSettingsChanged()
                                },
                                label = { Text("$pkg ×") },
                            )
                        }
                    }
                }
            }
        }

        // Hardware keyboard section
        SectionCard(title = "Bàn phím vật lý (Cover / Bluetooth)") {
            Text(
                text = "Phím tắt chuyển Việt ↔ Anh",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HwToggleShortcut.entries.forEach { sc ->
                    FilterChip(
                        selected = settings.hwToggleShortcut == sc,
                        onClick = {
                            preferences.setHwToggleShortcut(sc)
                            onSettingsChanged()
                        },
                        label = { Text(sc.label) },
                    )
                }
            }
            Text(
                text = "• Phím Esc khi đang gõ dở: lật Anh ↔ Việt cho từ đó và tự học.\n" +
                    "• Tự ẩn bàn phím ảo khi gắn cover/Bluetooth theo cài đặt hệ thống (giống Gboard).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Touch tablet keyboard section
        SectionCard(title = "Bàn phím cảm ứng (Tablet kiểu Gboard)") {
            Text("Chủ đề bàn phím", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { tm ->
                    FilterChip(
                        selected = settings.themeMode == tm,
                        onClick = {
                            preferences.setThemeMode(tm)
                            onSettingsChanged()
                        },
                        label = { Text(tm.label) },
                    )
                }
            }
            SwitchRow(
                title = "Bảng nhớ tạm (Clipboard kiểu Gboard)",
                subtitle = "Ghim mục quan trọng (lưu qua khởi động lại), mục chưa ghim tự xóa sau 1 giờ, không lưu mật khẩu",
                checked = settings.clipboardEnabled,
                onCheckedChange = {
                    preferences.setClipboardEnabled(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Gợi ý từ (kiểu Gboard)",
                subtitle = "Đoán từ đang gõ và từ tiếp theo, học từ anh hay dùng (không học ở ô mật khẩu/ẩn danh)",
                checked = settings.suggestionsEnabled,
                onCheckedChange = {
                    preferences.setSuggestionsEnabled(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Tự tính số gợi ý theo màn hình",
                subtitle = "Bật: màn hình rộng hiện nhiều gợi ý hơn (tối đa 7). Tắt: luôn 3 gợi ý như Gboard",
                checked = settings.autoSuggestionCount,
                onCheckedChange = {
                    preferences.setAutoSuggestionCount(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Hàng số cố định (1–0)",
                subtitle = "Bố cục 5 hàng giống Gboard tablet",
                checked = settings.showNumberRow,
                onCheckedChange = {
                    preferences.setShowNumberRow(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Bố cục tách đôi (Split)",
                subtitle = "Tách 2 nửa bàn phím tiện gõ hai ngón cái",
                checked = settings.splitLayout,
                onCheckedChange = {
                    preferences.setSplitLayout(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Rung nhẹ khi chạm phím",
                subtitle = "Phản hồi xúc giác lúc gõ",
                checked = settings.hapticEnabled,
                onCheckedChange = {
                    preferences.setHapticEnabled(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Vuốt Space để di chuyển con trỏ",
                subtitle = "Vuốt trái/phải trên phím Space (kiểu Gboard)",
                checked = settings.swipeSpaceCursor,
                onCheckedChange = {
                    preferences.setSwipeSpaceCursor(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Tự viết hoa đầu câu (cảm ứng)",
                subtitle = "Không can thiệp khi gõ bàn phím cứng hoặc RustDesk",
                checked = settings.autoCap,
                onCheckedChange = {
                    preferences.setAutoCap(it)
                    onSettingsChanged()
                },
            )
            SwitchRow(
                title = "Chạm đúp Space chèn dấu chấm",
                subtitle = "Gõ 2 lần Space nhanh → \". \"",
                checked = settings.doubleSpacePeriod,
                onCheckedChange = {
                    preferences.setDoubleSpacePeriod(it)
                    onSettingsChanged()
                },
            )

            HorizontalDivider()
            HeightSlider(
                label = "Chiều cao màn hình dọc: ${settings.portraitHeightPct}%",
                valuePct = settings.portraitHeightPct,
                onValueChange = {
                    preferences.setPortraitHeightPct(it)
                    onSettingsChanged()
                },
            )
            HeightSlider(
                label = "Chiều cao màn hình ngang: ${settings.landscapeHeightPct}%",
                valuePct = settings.landscapeHeightPct,
                onValueChange = {
                    preferences.setLandscapeHeightPct(it)
                    onSettingsChanged()
                },
            )
        }

        // Sandbox typing test
        SectionCard(title = "Ô gõ thử") {
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = {
                    Text("Gõ thử VNI (tie6ng1 Vie65t 2026) hoặc Telex (Tooi yeeu tieengs Vieejt)...")
                },
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LearnedDataTab(
    learnedStore: VgoLearnedStore,
    preferences: VgoPreferences,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(learnedStore.listAll()) }
    var selectedCategory by remember { mutableStateOf<LearnedCategory?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val count = context.contentResolver.openOutputStream(uri)?.use { out ->
                learnedStore.exportZip(out)
            } ?: 0
            Toast.makeText(context, "Đã xuất $count tệp dữ liệu tự học", Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val count = context.contentResolver.openInputStream(uri)?.use { input ->
                learnedStore.importZip(input)
            } ?: 0
            preferences.bumpLearnedRevision()
            items = learnedStore.listAll()
            Toast.makeText(context, "Đã nhập $count tệp (tương thích ~/.config/vgo)", Toast.LENGTH_SHORT).show()
        }
    }

    val filtered = remember(items, selectedCategory) {
        if (selectedCategory == null) items else items.filter { it.category == selectedCategory }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Định dạng giống hệt ~/.config/vgo/ trên Linux. Anh có thể xuất/nhập file ZIP để đồng bộ giữa PC và tablet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exportLauncher.launch("vgo-learned.zip") }) {
                Text("Xuất ZIP")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "*/*")) }) {
                Text("Nhập ZIP")
            }
            if (items.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text("Xóa hết", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("Tất cả (${items.size})") },
            )
            LearnedCategory.entries.forEach { cat ->
                val cnt = items.count { it.category == cat }
                if (cnt > 0) {
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text("${cat.label} ($cnt)") },
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = "Chưa có mục tự học nào. Khi anh bấm Esc (hoặc chip ↺) để lật Anh↔Việt, bộ gõ sẽ tự lưu ở đây.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            filtered.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.line, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${item.category.label} · ${item.relativePath}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                if (learnedStore.deleteItem(item)) {
                                    runCatching {
                                        VgoNative.ensureLoaded()
                                        VgoNative.nativeForgetEntry(item.relativePath, item.line)
                                    }
                                    preferences.bumpLearnedRevision()
                                    items = learnedStore.listAll()
                                }
                            }
                        ) {
                            Text("Xóa")
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Xóa toàn bộ dữ liệu tự học?") },
            text = { Text("Toàn bộ từ, cặp từ, lỗi gõ và ngoại lệ theo app đã học sẽ bị xóa.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        val existing = learnedStore.listAll()
                        learnedStore.clearAll()
                        runCatching {
                            VgoNative.ensureLoaded()
                            existing.forEach { VgoNative.nativeForgetEntry(it.relativePath, it.line) }
                        }
                        preferences.bumpLearnedRevision()
                        items = learnedStore.listAll()
                    }
                ) { Text("Xóa hết", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Hủy") }
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HeightSlider(
    label: String,
    valuePct: Int,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = valuePct.toFloat(),
            onValueChange = { onValueChange((it / 5f).roundToInt() * 5) },
            valueRange = 75f..130f,
            steps = 10,
        )
    }
}
