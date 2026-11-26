package dev.vgo.ime.clipboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import dev.vgo.ime.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Copies copied images into vgo's private storage (like Gboard) so they can still be pasted after the
 * source app's temporary URI grant is gone, and serves them to editors through vgo's own FileProvider.
 * Files are named by content hash, so copying the same image twice yields the same URI.
 */
class ClipImageStore(private val context: Context) {
    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Copies [source] in the background, then calls [done] on the main thread with the local URI. */
    fun import(source: Uri, mime: String, done: (String) -> Unit) {
        executor.execute {
            val local = runCatching { copy(source, mime) }
                .onFailure { Log.w(TAG, "copy image failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (local != null) mainHandler.post { done(local) }
        }
    }

    private fun copy(source: Uri, mime: String): String? {
        val tmp = File(dir, "import.tmp")
        val md = MessageDigest.getInstance("SHA-1")
        var total = 0L
        val ok = context.contentResolver.openInputStream(source)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return@use false
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
                true
            }
        } ?: false
        if (!ok || total == 0L) {
            tmp.delete()
            return null
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }.take(24)
        val file = File(dir, "$hash.${extensionFor(mime)}")
        if (file.exists()) tmp.delete() else if (!tmp.renameTo(file)) return null
        return uriFor(file)
    }

    private fun uriFor(file: File): String = FileProvider.getUriForFile(context, AUTHORITY, file).toString()

    fun isOurs(uri: String): Boolean = uri.startsWith("content://$AUTHORITY/")

    fun delete(uri: String) {
        if (!isOurs(uri)) return
        val name = Uri.parse(uri).lastPathSegment ?: return
        File(dir, name).takeIf { it.parentFile == dir }?.delete()
    }

    /** Removes stored images no entry refers to (unpinned history is RAM-only and gone after restart). */
    fun cleanup(keepUris: Set<String>) {
        executor.execute {
            dir.listFiles()?.forEach { f ->
                if (f.name == "import.tmp" || uriFor(f) !in keepUris) f.delete()
            }
        }
    }

    companion object {
        private const val TAG = "VgoClipImage"
        const val DIR_NAME = "clip-images"
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.clipfiles"
        const val MAX_BYTES = 30L * 1024 * 1024

        fun extensionFor(mime: String): String = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/bmp" -> "bmp"
            else -> "img"
        }
    }
}
