package com.example.plex_tv_app.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.example.plex_tv_app.ui.theme.palette
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Keeps up to ~48 MB of decoded posters in memory. */
private object ImageCache : LruCache<String, Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/**
 * Downloads images ahead of time (a few at once), so a grid about to appear shows its
 * artwork straight away instead of grey boxes.
 */
suspend fun prefetchImages(urls: List<String>) = coroutineScope {
    urls.distinct()
        .filter { ImageCache.get(it) == null }
        .chunked(6)
        .forEach { batch ->
            batch.map { url ->
                async(Dispatchers.IO) { download(url, transparent = false)?.let { ImageCache.put(url, it) } }
            }.awaitAll()
        }
}

/** Forget every downloaded image (Settings → About → Clear image cache). */
fun clearImageCache() = ImageCache.evictAll()

/**
 * A tiny image loader: downloads [url] in the background and fades it in,
 * showing [placeholder] until then.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    placeholder: Color = palette.raised,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    transparent: Boolean = false, // keep see-through areas (logos)
) {
    val bitmap by produceState(initialValue = url?.let { ImageCache.get(it) }, url) {
        // The URL changed (new row style, or a recycled card): drop the old picture, then load the new one.
        val cached = url?.let { ImageCache.get(it) }
        value = cached
        if (url == null || cached != null) return@produceState
        value = withContext(Dispatchers.IO) { download(url, transparent) }?.also { ImageCache.put(url, it) }
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(400), label = "imageFade")
    Box(modifier.background(placeholder)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

private fun download(url: String, transparent: Boolean): Bitmap? = runCatching {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8_000
    conn.readTimeout = 15_000
    try {
        conn.inputStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                // RGB_565 halves memory for posters; logos need alpha for their see-through parts.
                inPreferredConfig = if (transparent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            })
        }
    } finally {
        conn.disconnect()
    }
}.getOrNull()
