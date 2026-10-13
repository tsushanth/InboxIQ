package com.inboxiq.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.inboxiq.app.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes at most [maxDim] px on the long edge, so a 12 MP photo never becomes a 48 MB bitmap in a chat bubble. */
fun decodeScaled(context: Context, partUri: String, maxDim: Int): Bitmap? = runCatching {
    val uri = Uri.parse(partUri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrNull()

@Composable
fun MmsImage(partUri: String, modifier: Modifier = Modifier, maxDim: Int = 800, contentScale: ContentScale = ContentScale.Crop, description: String = "Photo in message") {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, partUri) {
        value = withContext(Dispatchers.IO) { decodeScaled(context, partUri, maxDim) }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = description, contentScale = contentScale, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.BrokenImage, contentDescription = "Photo could not be loaded", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MediaGalleryScreen(title: String, images: List<MessageEntity>, onBack: () -> Unit, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversation") }
            Column(Modifier.weight(1f)) {
                Text("Photos", style = MaterialTheme.typography.titleLarge)
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (images.isEmpty()) {
            StateMessage(
                Icons.Filled.PhotoLibrary, "No photos here yet",
                "Pictures sent or received in this conversation are collected here.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                items(images.size, key = { images[it].id }) { i ->
                    MmsImage(
                        images[i].imagePartUri!!, maxDim = 300,
                        description = "Photo from " + formatFullTimestamp(images[i].timestamp),
                        modifier = Modifier.aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable(onClickLabel = "Open photo") { onOpen(i) },
                    )
                }
            }
        }
    }
}

/** Full-screen viewer: swipe between photos, pinch or double-tap to zoom, share. */
@Composable
fun ImageViewerScreen(images: List<MessageEntity>, startIndex: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    if (images.isEmpty()) { onClose(); return }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
    val context = LocalContext.current
    var zoomed by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(images[page].imagePartUri!!, "Photo ${page + 1} of ${images.size}", onZoomChanged = { zoomed = it })
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close photo", tint = Color.White) }
            Text(
                "${pager.currentPage + 1} of ${images.size}",
                color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { shareImage(context, images[pager.currentPage].imagePartUri!!) }) {
                Icon(Icons.Filled.Share, contentDescription = "Share photo", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ZoomableImage(partUri: String, description: String, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f }
                    onZoomChanged(scale > 1f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offsetX = 0f; offsetY = 0f
                    onZoomChanged(scale > 1f)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        MmsImage(
            partUri, maxDim = 2048, contentScale = ContentScale.Fit, description = description,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY },
        )
    }
}

/** Shares a copy through the app's own FileProvider, so the picker never needs access to the SMS provider. */
fun shareImage(context: Context, partUri: String) {
    runCatching {
        val dir = File(context.filesDir, "mms_images").apply { mkdirs() }
        val out = File(dir, "share_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(Uri.parse(partUri))?.use { input -> out.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
