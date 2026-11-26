package dev.vgo.ime.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ClipboardEntry(
    val id: Long,
    val text: String? = null,
    val imageUri: String? = null,
    val imageMime: String? = null,
    val timestampMs: Long,
    val pinned: Boolean = false,
) {
    val isImage: Boolean get() = !imageUri.isNullOrBlank()

    fun previewLabel(): String = when {
        !text.isNullOrBlank() -> text.replace('\n', ' ').trim()
        isImage -> "Ảnh đã sao chép"
        else -> ""
    }
}

/**
 * Gboard-style local clipboard manager:
 * - Pinned entries: persisted to app-private storage (vgo-clipboard-pinned.json), never auto-expire,
 *   never removed by "Clear all", always ordered at top.
 * - Unpinned entries: RAM-only (privacy-first, cleared on reboot), auto-expire after 1 hour (like Gboard).
 * - Toolbar suggestion chip: visible for 3 minutes after copy, immediately hidden once pasted.
 * - Strictly ignores EXTRA_IS_SENSITIVE (Android 13+ password managers / password fields).
 * - Supports both text and image/screenshot URIs (for commitContent).
 */
class LocalClipboardHistory(
    private val context: Context?,
    private val storageFile: File? = context?.filesDir?.let { File(it, "vgo-clipboard-pinned.json") },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val imageStore: ClipImageStore? = context?.let { ClipImageStore(it) },
    private val onChanged: () -> Unit,
) {
    private val manager = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val entries = mutableListOf<ClipboardEntry>()
    private var nextId = 1L
    private var enabled = true
    private var dismissedSuggestionId: Long = -1L

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (enabled) captureCurrentClip()
    }

    init {
        loadPinnedFromDisk()
        imageStore?.cleanup(entries.mapNotNullTo(HashSet()) { it.imageUri })
    }

    fun setEnabled(isEnabled: Boolean) {
        if (enabled == isEnabled) return
        enabled = isEnabled
        if (!enabled) {
            dismissedSuggestionId = entries.firstOrNull { !it.pinned }?.id ?: -1L
        }
        onChanged()
    }

    fun isEnabled(): Boolean = enabled

    fun start() {
        if (enabled) captureCurrentClip()
        try {
            manager?.addPrimaryClipChangedListener(listener)
        } catch (_: SecurityException) {}
    }

    fun stop() {
        try {
            manager?.removePrimaryClipChangedListener(listener)
        } catch (_: Exception) {}
    }

    fun items(): List<ClipboardEntry> {
        purgeExpired()
        val pinned = entries.filter { it.pinned }.sortedByDescending { it.timestampMs }
        val unpinned = entries.filter { !it.pinned }.sortedByDescending { it.timestampMs }
        return pinned + unpinned
    }

    /** Returns the latest recent entry eligible for the Gboard toolbar suggestion chip, or null. */
    fun activeSuggestion(isPasswordField: Boolean): ClipboardEntry? {
        if (!enabled || isPasswordField) return null
        purgeExpired()
        val latest = entries.maxByOrNull { it.timestampMs } ?: return null
        if (latest.id == dismissedSuggestionId) return null
        if (clockMs() - latest.timestampMs > CHIP_SUGGESTION_TTL_MS) return null
        return latest
    }

    fun dismissSuggestion(entryId: Long) {
        dismissedSuggestionId = entryId
        onChanged()
    }

    fun togglePin(id: Long) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cur = entries[idx]
        entries[idx] = cur.copy(pinned = !cur.pinned)
        savePinnedToDisk()
        onChanged()
    }

    fun remove(id: Long) {
        val removed = entries.firstOrNull { it.id == id } ?: return
        entries.remove(removed)
        releaseImages(listOf(removed))
        if (removed.pinned) savePinnedToDisk()
        onChanged()
    }

    /** Clears all UNPINNED items (preserves pinned entries, matching Gboard). */
    fun clearUnpinned() {
        val changed = dropWhere { !it.pinned }
        if (changed) {
            dismissedSuggestionId = -1L
            onChanged()
        }
    }

    fun purgeExpired(): Boolean {
        val cutoff = clockMs() - UNPINNED_TTL_MS
        return dropWhere { !it.pinned && it.timestampMs < cutoff }
    }

    private fun dropWhere(pred: (ClipboardEntry) -> Boolean): Boolean {
        val dropped = entries.filter(pred)
        if (dropped.isEmpty()) return false
        entries.removeAll(dropped.toSet())
        releaseImages(dropped)
        return true
    }

    /** Deletes vgo's stored copy of dropped images unless another entry still shows the same file. */
    private fun releaseImages(dropped: List<ClipboardEntry>) {
        val store = imageStore ?: return
        val stillUsed = entries.mapNotNullTo(HashSet()) { it.imageUri }
        dropped.mapNotNull { it.imageUri }.filter { it !in stillUsed }.forEach { store.delete(it) }
    }

    private fun captureCurrentClip() {
        val cm = manager ?: return
        val clip = try {
            cm.primaryClip
        } catch (_: SecurityException) {
            return
        } ?: return

        if (isSensitive(clip.description)) return
        if (clip.itemCount <= 0) return

        val item = clip.getItemAt(0) ?: return
        val uri = item.uri
        if (uri != null) {
            val mime = resolveImageMime(clip.description, uri.toString())
            if (mime != null) {
                val store = imageStore
                if (store == null || store.isOurs(uri.toString())) {
                    addEntryInternal(text = null, imageUri = uri.toString(), imageMime = mime)
                } else {
                    // The source app's URI grant is temporary: keep our own copy (like Gboard).
                    store.import(uri, mime) { local ->
                        if (enabled) addEntryInternal(text = null, imageUri = local, imageMime = mime)
                    }
                }
                return
            }
        }

        val rawText = item.text?.toString() ?: return
        val text = rawText.trim()
        if (text.isEmpty() || text.length > MAX_CLIP_CHARS) return
        addEntryInternal(text = text, imageUri = null, imageMime = null)
    }

    internal fun addTextForTest(text: String, sensitive: Boolean = false) {
        if (!enabled || sensitive) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CLIP_CHARS) return
        addEntryInternal(text = trimmed, imageUri = null, imageMime = null)
    }

    internal fun addImageForTest(uri: String, mime: String, sensitive: Boolean = false) {
        if (!enabled || sensitive) return
        addEntryInternal(text = null, imageUri = uri, imageMime = mime)
    }

    private fun addEntryInternal(text: String?, imageUri: String?, imageMime: String?) {
        purgeExpired()
        val now = clockMs()
        val existingIdx = entries.indexOfFirst {
            (text != null && it.text == text) || (imageUri != null && it.imageUri == imageUri)
        }
        if (existingIdx >= 0) {
            val old = entries[existingIdx]
            if (now - old.timestampMs < 1500L) return
            entries[existingIdx] = old.copy(timestampMs = now)
            dismissedSuggestionId = -1L
            if (old.pinned) savePinnedToDisk()
            onChanged()
            return
        }

        entries.add(
            ClipboardEntry(
                id = nextId++,
                text = text,
                imageUri = imageUri,
                imageMime = imageMime,
                timestampMs = now,
                pinned = false,
            )
        )
        dismissedSuggestionId = -1L

        // Cap unpinned items at MAX_UNPINNED_ENTRIES (never evict pinned items).
        val unpinned = entries.filter { !it.pinned }.sortedByDescending { it.timestampMs }
        if (unpinned.size > MAX_UNPINNED_ENTRIES) {
            val toDrop = unpinned.drop(MAX_UNPINNED_ENTRIES).mapTo(HashSet()) { it.id }
            dropWhere { it.id in toDrop }
        }
        onChanged()
    }

    private fun resolveImageMime(desc: ClipDescription?, uriStr: String): String? {
        if (desc != null && desc.mimeTypeCount > 0) {
            for (i in 0 until desc.mimeTypeCount) {
                val m = desc.getMimeType(i)
                if (m.startsWith("image/")) return m
            }
            // ClipDescription already declares explicit non-image MIME types; avoid probing unexported providers.
            return null
        }
        val resolved = runCatching {
            context?.contentResolver?.getType(android.net.Uri.parse(uriStr))
        }.getOrNull()
        return resolved?.takeIf { it.startsWith("image/") }
    }

    private fun isSensitive(desc: ClipDescription?): Boolean {
        val extras = desc?.extras ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) return true
        }
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private fun loadPinnedFromDisk() {
        val f = storageFile ?: return
        if (!f.isFile) return
        runCatching {
            val decoder = Base64.getDecoder()
            f.readLines().forEach { line ->
                val parts = line.split('\t')
                if (parts.size != 4) return@forEach
                val ts = parts[0].toLongOrNull() ?: clockMs()
                val kind = parts[1]
                val mime = parts[2].takeIf { it.isNotEmpty() }
                val payload = String(decoder.decode(parts[3]), StandardCharsets.UTF_8)
                if (kind == "T" && payload.isNotEmpty()) {
                    entries.add(ClipboardEntry(id = nextId++, text = payload, timestampMs = ts, pinned = true))
                } else if (kind == "I" && payload.isNotEmpty()) {
                    entries.add(
                        ClipboardEntry(
                            id = nextId++,
                            imageUri = payload,
                            imageMime = mime ?: "image/*",
                            timestampMs = ts,
                            pinned = true,
                        )
                    )
                }
            }
            // Don't pop up old pinned items as fresh copy suggestions on boot.
            dismissedSuggestionId = entries.maxByOrNull { it.timestampMs }?.id ?: -1L
        }
    }

    private fun savePinnedToDisk() {
        val f = storageFile ?: return
        runCatching {
            val encoder = Base64.getEncoder()
            val lines = entries.filter { it.pinned }.mapNotNull { e ->
                when {
                    e.text != null -> {
                        val b64 = encoder.encodeToString(e.text.toByteArray(StandardCharsets.UTF_8))
                        "${e.timestampMs}\tT\t\t$b64"
                    }
                    e.imageUri != null -> {
                        val b64 = encoder.encodeToString(e.imageUri.toByteArray(StandardCharsets.UTF_8))
                        "${e.timestampMs}\tI\t${e.imageMime.orEmpty()}\t$b64"
                    }
                    else -> null
                }
            }
            f.parentFile?.mkdirs()
            f.writeText(lines.joinToString("\n"))
        }
    }

    companion object {
        const val MAX_UNPINNED_ENTRIES = 20
        const val MAX_CLIP_CHARS = 4096
        const val UNPINNED_TTL_MS = 60L * 60L * 1000L // 1 hour (like Gboard)
        const val CHIP_SUGGESTION_TTL_MS = 3L * 60L * 1000L // 3 minutes (like Gboard)
    }
}
