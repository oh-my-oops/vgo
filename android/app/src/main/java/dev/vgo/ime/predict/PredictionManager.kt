package dev.vgo.ime.predict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the per-language models (loaded off the main thread from assets/predict/<lang>.txt)
 * and the user's history. All public methods run on the main thread.
 */
class PredictionManager(
    private val context: Context,
    private val userDir: File,
    private val onModelLoaded: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val models = HashMap<String, NgramModel>()
    private val loading = HashSet<String>()
    private val histories = HashMap<String, UserHistory>()
    private var lastLearnSig: String? = null

    fun preload(lang: String) {
        if (lang in models || lang in loading) return
        loading.add(lang)
        executor.execute {
            val t0 = System.nanoTime()
            val model = runCatching {
                context.assets.open("predict/$lang.txt").bufferedReader().use { NgramModel.parse(it) }
            }.onFailure { Log.w(TAG, "load $lang failed", it) }.getOrNull()
            Log.i(TAG, "model $lang: ${model?.size} words in ${(System.nanoTime() - t0) / 1_000_000} ms")
            mainHandler.post {
                loading.remove(lang)
                if (model != null) {
                    models[lang] = model
                    onModelLoaded()
                }
            }
        }
    }

    private fun history(lang: String): UserHistory =
        histories.getOrPut(lang) { UserHistory(File(userDir, "predict-$lang.txt")) }

    fun suggest(lang: String, ctx: PredictContext, max: Int): List<String> {
        val model = models[lang] ?: run { preload(lang); return emptyList() }
        return WordPredictor(model, history(lang)).suggest(ctx, max)
    }

    /** Learns the word the user just finished (called when [before] ends right after a word). */
    fun learnFinishedWord(lang: String, before: String) {
        if (before.isEmpty() || PredictContext.isWordChar(before.last())) return
        var end = before.length
        while (end > 0 && !PredictContext.isWordChar(before[end - 1])) {
            // Only a short run of spaces/punctuation may follow the word ("chào, " / "ok. ").
            if (before.length - end >= 3) return
            end--
        }
        val head = before.substring(0, end)
        val ctx = PredictContext.parse(head)
        if (ctx.partial.isEmpty()) return
        val sig = "${head.length}|${ctx.prev1}|${ctx.partial}"
        if (sig == lastLearnSig) return
        lastLearnSig = sig
        val prev = ctx.prev1?.takeIf { it != PredictContext.SENTENCE_START }
        history(lang).learn(prev, ctx.partial)
    }

    fun resetLearnSignature() {
        lastLearnSig = null
    }

    fun save() {
        histories.values.forEach { it.save() }
    }

    companion object {
        private const val TAG = "VgoPredict"
    }
}
