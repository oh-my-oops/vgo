package dev.vgo.ime.engine

import android.content.Context
import dev.vgo.ime.BuildConfig
import java.io.File

data class VgoDataDirs(
    val baseDir: File,
    val userDir: File,
)

object AssetDataInstaller {
    private val baseAssetFiles = listOf(
        "en-base.txt",
        "vn-base.txt",
        "bigram-base.txt",
        "vn-syllables.txt",
    )

    fun ensureInstalled(context: Context): VgoDataDirs {
        val baseDir = File(context.filesDir, "vgo-base").apply { mkdirs() }
        val userDir = File(context.filesDir, "vgo-user").apply { mkdirs() }
        File(userDir, "apps").mkdirs()

        val stampFile = File(baseDir, ".stamp")
        val apkUpdateTime = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val expectedStamp = "${BuildConfig.VERSION_CODE}-${BuildConfig.VERSION_NAME}-$apkUpdateTime"
        val needsCopy = !stampFile.exists() ||
            stampFile.readText().trim() != expectedStamp ||
            baseAssetFiles.any { !File(baseDir, it).exists() }

        if (needsCopy) {
            for (name in baseAssetFiles) {
                val outFile = File(baseDir, name)
                context.assets.open(name).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            stampFile.writeText(expectedStamp)
        }

        return VgoDataDirs(baseDir = baseDir, userDir = userDir)
    }
}
