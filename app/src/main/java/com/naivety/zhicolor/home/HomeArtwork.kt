package com.naivety.zhicolor.home

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode once for the shared background and cover; retain the previous image while decoding. */
@Composable
internal fun rememberHomeArtwork(bytes: ByteArray?, mediaAvailable: Boolean): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, bytes, mediaAvailable) {
        if (!mediaAvailable) {
            value = null
            return@produceState
        }
        // Publish only fully decoded artwork. Cancelled obsolete requests never replace it.
        val decoded = withContext(Dispatchers.Default) {
            bytes?.let { data ->
                runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()
            }
        }
        if (decoded != null) value = decoded
    }
