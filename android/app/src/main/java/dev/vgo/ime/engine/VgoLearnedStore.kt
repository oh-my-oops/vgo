package dev.vgo.ime.engine

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class LearnedCategory(val fileName: String, val label: String) {
    VNI_RAW("vni-raw.txt", "VNI giữ thô"),
    VNI_VN("vni-vn.txt", "VNI ép Việt"),
    VNI_BIGRAM("vni-bigram.txt", "VNI cặp từ"),
    VNI_TYPOS("vni-typos.txt", "VNI lỗi gõ"),
    TELEX_EN("words.txt", "Telex từ Anh"),
    TELEX_VN("vn-words.txt", "Telex ép Việt"),
    TELEX_BIGRAM("bigram.txt", "Telex cặp từ"),
    TELEX_TYPOS("typos.txt", "Telex lỗi gõ"),
    PER_APP("apps", "Riêng từng app");
}

data class LearnedItem(
    val category: LearnedCategory,
    val relativePath: String,
    val line: String,
)

/**
 * Manages files under filesDir/vgo-user/ with 100% format compatibility with Linux ~/.config/vgo/.
 */
class VgoLearnedStore(private val userDir: File) {

    fun listAll(): List<LearnedItem> {
        val out = mutableListOf<LearnedItem>()
        for (cat in LearnedCategory.entries) {
            if (cat == LearnedCategory.PER_APP) continue
            val f = File(userDir, cat.fileName)
            if (!f.exists()) continue
            f.readLines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    out += LearnedItem(cat, cat.fileName, line)
                }
        }
        val appsDir = File(userDir, "apps")
        if (appsDir.isDirectory) {
            appsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val rel = "apps/${file.name}"
                    file.readLines().map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { line ->
                            out += LearnedItem(LearnedCategory.PER_APP, rel, line)
                        }
                }
        }
        return out
    }

    fun deleteItem(item: LearnedItem): Boolean {
        val file = File(userDir, item.relativePath)
        if (!file.exists()) return false
        val lines = file.readLines()
        var removed = false
        val kept = lines.filter { raw ->
            if (!removed && raw.trim() == item.line) {
                removed = true
                false
            } else {
                true
            }
        }
        if (!removed) return false
        if (kept.all { it.trim().isEmpty() || it.trim().startsWith("#") }) {
            file.delete()
        } else {
            file.writeText(kept.joinToString("\n") + "\n")
        }
        return true
    }

    fun clearAll() {
        for (cat in LearnedCategory.entries) {
            if (cat == LearnedCategory.PER_APP) continue
            File(userDir, cat.fileName).delete()
        }
        val appsDir = File(userDir, "apps")
        if (appsDir.isDirectory) {
            appsDir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Exports vgo-user/ as a ZIP whose layout matches Linux ~/.config/vgo/ directly. */
    fun exportZip(output: OutputStream): Int {
        var count = 0
        ZipOutputStream(output).use { zos ->
            fun addFile(relPath: String, file: File) {
                if (!file.isFile) return
                zos.putNextEntry(ZipEntry(relPath))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
            for (cat in LearnedCategory.entries) {
                if (cat == LearnedCategory.PER_APP) continue
                addFile(cat.fileName, File(userDir, cat.fileName))
            }
            val appsDir = File(userDir, "apps")
            if (appsDir.isDirectory) {
                appsDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".txt") }
                    ?.sortedBy { it.name }
                    ?.forEach { f -> addFile("apps/${f.name}", f) }
            }
        }
        return count
    }

    /** Imports a ZIP created by exportZip() or zipped directly from Linux ~/.config/vgo/. */
    fun importZip(input: InputStream): Int {
        val allowedTopFiles = LearnedCategory.entries
            .filter { it != LearnedCategory.PER_APP }
            .map { it.fileName }
            .toSet()

        var imported = 0
        userDir.mkdirs()
        File(userDir, "apps").mkdirs()

        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val normalized = entry.name.trimStart('/').removePrefix("vgo/")
                val target: File? = when {
                    normalized in allowedTopFiles -> File(userDir, normalized)
                    normalized.startsWith("apps/") &&
                        !normalized.contains("..") &&
                        normalized.endsWith(".txt") &&
                        normalized.indexOf('/', startIndex = 5) == -1 -> {
                        File(userDir, normalized)
                    }
                    else -> null
                }
                if (target != null) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zis.copyTo(out) }
                    imported++
                }
                zis.closeEntry()
            }
        }
        return imported
    }
}
