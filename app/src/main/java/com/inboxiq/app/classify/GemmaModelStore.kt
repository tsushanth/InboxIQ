package com.inboxiq.app.classify

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Terminal + in-progress states the Settings screen needs to render the MID-tier download flow. */
sealed interface ModelDownloadState {
    data object NotDownloaded : ModelDownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState
    data object WaitingForWifi : ModelDownloadState
    data object RequiresConfirmation : ModelDownloadState
    data object Ready : ModelDownloadState
    data class Failed(val errorCode: Int) : ModelDownloadState
}

/**
 * Wraps Play Asset Delivery for the gemma_model_pack on-demand asset pack (see
 * gemma_model_pack/build.gradle.kts). The model file is baked into our own Play
 * Console release, not fetched from Hugging Face at runtime — this module never
 * needs the INTERNET permission; Play's own infrastructure performs the transfer.
 * See docs/gemma-model-pack.md for how the model file gets into the pack.
 */
object GemmaModelStore {
    private const val PACK_NAME = "gemma_model_pack"
    const val MODEL_FILE_NAME = "gemma3-270m-it-q8.litertlm"

    /** Null if the pack isn't fully downloaded yet — callers must check before constructing GemmaClassifier. */
    fun modelPath(context: Context): String? {
        val manager = AssetPackManagerFactory.getInstance(context)
        val location = manager.getPackLocation(PACK_NAME) ?: return null
        return "${location.assetsPath()}/$MODEL_FILE_NAME"
    }

    fun isReady(context: Context): Boolean = modelPath(context) != null

    fun requestDownload(context: Context) {
        AssetPackManagerFactory.getInstance(context).fetch(listOf(PACK_NAME))
    }

    fun cancelDownload(context: Context) {
        AssetPackManagerFactory.getInstance(context).cancel(listOf(PACK_NAME))
    }

    /** Emits state updates for as long as collected — Settings screen should collect this while the download row is visible. */
    fun observeDownloadState(context: Context): Flow<ModelDownloadState> = callbackFlow {
        val manager = AssetPackManagerFactory.getInstance(context)
        val listener = { state: AssetPackState ->
            if (state.name() == PACK_NAME) {
                trySend(state.toDownloadState())
            }
        }
        manager.registerListener(listener)
        awaitClose { manager.unregisterListener(listener) }
    }

    private fun AssetPackState.toDownloadState(): ModelDownloadState = when (status()) {
        AssetPackStatus.COMPLETED -> ModelDownloadState.Ready
        AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING ->
            ModelDownloadState.Downloading(bytesDownloaded(), totalBytesToDownload())
        AssetPackStatus.WAITING_FOR_WIFI -> ModelDownloadState.WaitingForWifi
        AssetPackStatus.REQUIRES_USER_CONFIRMATION -> ModelDownloadState.RequiresConfirmation
        AssetPackStatus.FAILED -> ModelDownloadState.Failed(errorCode())
        AssetPackStatus.CANCELED, AssetPackStatus.NOT_INSTALLED, AssetPackStatus.PENDING ->
            ModelDownloadState.NotDownloaded
        else -> ModelDownloadState.NotDownloaded
    }
}
