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

    private fun defaultClassifier(context: Context): MessageClassifier = try {
        OnnxEncoderClassifier(context)
    } catch (e: Exception) {
        Log.w(TAG, "Falling back to heuristics-only classifier", e)
        StubClassifier()
    }
}
