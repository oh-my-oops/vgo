package dev.vgo.ime.engine

import dev.vgo.ime.clipboard.LocalClipboardHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VgoM3StoreAndClipboardTest {

    @Test
    fun learnedStoreListDeleteExportAndImportRoundTrip() {
        val dir1 = Files.createTempDirectory("vgo-store-1-").toFile()
        File(dir1, "vni-raw.txt").writeText("a4\nmp3\n")
        File(dir1, "words.txt").writeText("rustdesk\n")
        File(dir1, "apps").apply { mkdirs() }
        File(dir1, "apps/com.carriez.flutter_hbb.txt").writeText("# header\nvni a4 raw\n")

        val store1 = VgoLearnedStore(dir1)
        assertEquals(4, store1.listAll().size)

        val baos = ByteArrayOutputStream()
        assertEquals(3, store1.exportZip(baos))

        val dir2 = Files.createTempDirectory("vgo-store-2-").toFile()
        val store2 = VgoLearnedStore(dir2)
        assertEquals(3, store2.importZip(ByteArrayInputStream(baos.toByteArray())))
        assertEquals(4, store2.listAll().size)

        val target = store2.listAll().first { it.line == "mp3" }
        assertTrue(store2.deleteItem(target))
        assertEquals(3, store2.listAll().size)

        store2.clearAll()
        assertTrue(store2.listAll().isEmpty())
    }

    @Test
    fun learnedStoreImportAcceptsLinuxPrefixAndRejectsPathTraversal() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("vgo/words.txt"))
            zos.write("linuxword\n".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("bad".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("apps/../../escape.txt"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
        }

        val dir = Files.createTempDirectory("vgo-store-sec-").toFile()
        val store = VgoLearnedStore(dir)
        assertEquals(1, store.importZip(ByteArrayInputStream(baos.toByteArray())))
        assertEquals(listOf("linuxword"), store.listAll().map { it.line })
        assertFalse(File(dir.parentFile, "evil.txt").exists())
    }

    @Test
    fun gboardClipboardPinPersistenceOneHourExpiryThreeMinChipAndSensitiveFilter() {
        val pinFile = File(Files.createTempDirectory("vgo-clip-").toFile(), "pinned.json")
        var now = 1_000_000L
        var changes = 0
        val hist = LocalClipboardHistory(
            context = null,
            storageFile = pinFile,
            clockMs = { now },
            onChanged = { changes++ },
        )

        // Sensitive clip (password) MUST NEVER be stored or suggested.
        hist.addTextForTest("TopSecretPass!", sensitive = true)
        assertTrue(hist.items().isEmpty())
        assertNull(hist.activeSuggestion(isPasswordField = false))

        // Add two items
        hist.addTextForTest("pinned-note")
        now += 5_000L
        hist.addTextForTest("recent-note")
        assertEquals(listOf("recent-note", "pinned-note"), hist.items().map { it.text })

        // Toolbar chip shows latest item on normal fields, NEVER on password fields.
        val sug = hist.activeSuggestion(isPasswordField = false)
        assertNotNull(sug)
        assertEquals("recent-note", sug!!.text)
        assertNull(hist.activeSuggestion(isPasswordField = true))

        // Dismissing suggestion hides chip immediately.
        hist.dismissSuggestion(sug.id)
        assertNull(hist.activeSuggestion(isPasswordField = false))

        // New copy shows chip again; expires after 3 minutes (CHIP_SUGGESTION_TTL_MS).
        now += 5_000L
        hist.addImageForTest("content://media/screenshot/1", "image/png")
        assertTrue(hist.activeSuggestion(isPasswordField = false)?.isImage == true)
        now += LocalClipboardHistory.CHIP_SUGGESTION_TTL_MS + 1_000L
        assertNull(hist.activeSuggestion(isPasswordField = false))

        // Pin "pinned-note": moves to top, survives clearUnpinned(), and persists across restart.
        val pinTarget = hist.items().first { it.text == "pinned-note" }
        hist.togglePin(pinTarget.id)
        assertEquals("pinned-note", hist.items().first().text)
        assertTrue(hist.items().first().pinned)

        // Advance past 1 hour: unpinned items auto-expire, pinned item remains!
        now += LocalClipboardHistory.UNPINNED_TTL_MS + 1_000L
        assertEquals(listOf("pinned-note"), hist.items().map { it.text })

        // Add another unpinned item, clearUnpinned keeps pinned item intact.
        hist.addTextForTest("temp")
        assertEquals(2, hist.items().size)
        hist.clearUnpinned()
        assertEquals(listOf("pinned-note"), hist.items().map { it.text })

        // Reload from disk (simulates reboot/process restart): pinned item restored!
        val reloaded = LocalClipboardHistory(
            context = null,
            storageFile = pinFile,
            clockMs = { now },
            onChanged = {},
        )
        assertEquals(listOf("pinned-note"), reloaded.items().map { it.text })
        assertTrue(reloaded.items().first().pinned)

        // Disabled clipboard stops capturing and hides suggestion chip.
        reloaded.setEnabled(false)
        reloaded.addTextForTest("should-not-capture")
        assertEquals(1, reloaded.items().size)
        assertNull(reloaded.activeSuggestion(isPasswordField = false))
    }
}
