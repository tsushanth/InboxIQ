package com.inboxiq.app.classify

import android.content.Context
import android.util.Log

/** Picks the classifier for the current settings tier — falls back to DEFAULT if MID is selected but its model isn't downloaded yet. */
object ClassifierFactory {
    private const val TAG = "ClassifierFactory"

    fun create(context: Context): MessageClassifier {
        val tier = ClassifierTierPreference.get(context)
        if (tier == ClassifierTier.MID) {
            val modelPath = GemmaModelStore.modelPath(context)
            if (modelPath != null) {
                return try {
                    GemmaClassifier(modelPath)
                } catch (e: Exception) {
                    Log.w(TAG, "Gemma classifier failed to load, falling back to default", e)
                    defaultClassifier(context)
                }
            }
            Log.w(TAG, "MID tier selected but model not downloaded yet, falling back to default")
        }
        return defaultClassifier(context)
    }

    /**
     * Rules plus the bert-tiny model. The model is loaded on first use, not here: a pass whose messages
     * the rules settle never pays for it, and if loading fails the rules keep working on their own.
     */
    private fun defaultClassifier(context: Context): MessageClassifier {
        val app = context.applicationContext
        return TriageClassifier(
            LazySpamScorer {
                try {
                    OnnxSpamScorer.load(app)
                } catch (e: Throwable) {
                    Log.w(TAG, "Spam model unavailable, using rules only", e)
                    NO_MODEL
                }
            },
        )
    }

    /** Stands in when the model cannot load; NaN means "no opinion" to [TriageEngine]. */
    private val NO_MODEL = SpamScorer { Float.NaN }
}
