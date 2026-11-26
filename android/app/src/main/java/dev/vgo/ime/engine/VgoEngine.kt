package dev.vgo.ime.engine

enum class InputMethodMode(val prefValue: String, val shortLabel: String) {
    VNI("vni", "VNI"),
    TELEX("telex", "Telex");

    companion object {
        fun fromPref(value: String?): InputMethodMode =
            entries.firstOrNull { it.prefValue == value } ?: VNI
    }
}

data class EngineSnapshot(
    val empty: Boolean = true,
    val display: String = "",
    val raw: String = "",
    val altDisplay: String = "",
    val showingRaw: Boolean = false,
    val corrected: Boolean = false,
) {
    val hasFlipCandidate: Boolean
        get() = !empty && altDisplay.isNotEmpty() && altDisplay != display
}

data class RevertResult(
    val reverted: Boolean = false,
    val committedToDelete: String = "",
    val replacement: String = "",
)

interface VgoEngineBridge {
    fun init(baseDir: String, userDir: String)
    fun setConfig(mode: InputMethodMode, oldStyleTone: Boolean, wAlwaysU: Boolean)
    fun setApp(packageName: String)
    fun isEmpty(): Boolean
    fun acceptsChar(ch: Char, isNumpad: Boolean): Boolean
    fun key(ch: Char): EngineSnapshot
    fun backspace(): Boolean
    fun flipAndLearn(): Boolean
    fun finalizeWord(boundaryCodePoint: Int)
    fun tryRevertCorrection(textBeforeCursor: String): RevertResult
    fun clearCanRevert()
    fun reset()
    fun clearContext()
    fun snapshot(): EngineSnapshot
    fun reloadUserFiles()
    fun forgetEntry(relativePath: String, line: String)
}

object VgoNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val explicitPath = System.getProperty("vgo.jni.lib")
            if (!explicitPath.isNullOrBlank()) {
                System.load(explicitPath)
            } else {
                System.loadLibrary("vgo_jni")
            }
            loaded = true
        }
    }

    external fun nativeInit(baseDir: String, userDir: String)
    external fun nativeSetConfig(vniMode: Boolean, oldStyleTone: Boolean, wAlwaysU: Boolean)
    external fun nativeSetApp(packageName: String)
    external fun nativeIsEmpty(): Boolean
    external fun nativeAcceptsChar(ch: Char, isNumpad: Boolean): Boolean
    external fun nativeKey(ch: Char): Array<String>
    external fun nativeBackspace(): Boolean
    external fun nativeFlipAndLearn(): Boolean
    external fun nativeFinalize(boundaryCodePoint: Int)
    external fun nativeTryRevertCorrection(textBeforeCursor: String): Array<String>?
    external fun nativeClearCanRevert()
    external fun nativeReset()
    external fun nativeClearContext()
    external fun nativeSnapshot(): Array<String>
    external fun nativeReloadUserFiles()
    external fun nativeForgetEntry(relativePath: String, line: String)
}

class NativeVgoEngineBridge : VgoEngineBridge {
    init {
        VgoNative.ensureLoaded()
    }

    override fun init(baseDir: String, userDir: String) {
        VgoNative.nativeInit(baseDir, userDir)
    }

    override fun setConfig(mode: InputMethodMode, oldStyleTone: Boolean, wAlwaysU: Boolean) {
        VgoNative.nativeSetConfig(mode == InputMethodMode.VNI, oldStyleTone, wAlwaysU)
    }

    override fun setApp(packageName: String) {
        VgoNative.nativeSetApp(packageName)
    }

    override fun isEmpty(): Boolean = VgoNative.nativeIsEmpty()

    override fun acceptsChar(ch: Char, isNumpad: Boolean): Boolean =
        VgoNative.nativeAcceptsChar(ch, isNumpad)

    override fun key(ch: Char): EngineSnapshot = parseSnapshot(VgoNative.nativeKey(ch))

    override fun backspace(): Boolean = VgoNative.nativeBackspace()

    override fun flipAndLearn(): Boolean = VgoNative.nativeFlipAndLearn()

    override fun finalizeWord(boundaryCodePoint: Int) {
        VgoNative.nativeFinalize(boundaryCodePoint)
    }

    override fun tryRevertCorrection(textBeforeCursor: String): RevertResult {
        val arr = VgoNative.nativeTryRevertCorrection(textBeforeCursor) ?: return RevertResult()
        return RevertResult(
            reverted = true,
            committedToDelete = arr[0],
            replacement = arr[1],
        )
    }

    override fun clearCanRevert() {
        VgoNative.nativeClearCanRevert()
    }

    override fun reset() {
        VgoNative.nativeReset()
    }

    override fun clearContext() {
        VgoNative.nativeClearContext()
    }

    override fun snapshot(): EngineSnapshot = parseSnapshot(VgoNative.nativeSnapshot())

    override fun reloadUserFiles() {
        VgoNative.nativeReloadUserFiles()
    }

    override fun forgetEntry(relativePath: String, line: String) {
        VgoNative.nativeForgetEntry(relativePath, line)
    }

    private fun parseSnapshot(arr: Array<String>): EngineSnapshot = EngineSnapshot(
        empty = arr[0] == "1",
        display = arr[1],
        raw = arr[2],
        altDisplay = arr[3],
        showingRaw = arr[4] == "1",
        corrected = arr[5] == "1",
    )
}
