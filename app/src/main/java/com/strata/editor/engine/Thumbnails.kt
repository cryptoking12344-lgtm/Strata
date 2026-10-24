package com.strata.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Small frame grabs for the timeline and project cards. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(2)

    /** [path] is a proxy file if one exists (much faster to read than an 8K original). */
    suspend fun frame(context: Context, uri: String, path: String?, timeUs: Long, isImage: Boolean, heightPx: Int = 120): Bitmap? {
        val bucket = timeUs / 500_000
        val key = "$uri@$bucket@$heightPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                cache.get(key) ?: runCatching { load(context, uri, path, timeUs, isImage, heightPx) }.getOrNull()?.also { cache.put(key, it) }
            }
        }
    }

    private fun load(context: Context, uri: String, path: String?, timeUs: Long, isImage: Boolean, h: Int): Bitmap? {
        if (isImage) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outHeight / (sample * 2) >= h) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            return context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, o2) }
        }
        val r = MediaMetadataRetriever()
        try {
            if (path != null && File(path).exists()) r.setDataSource(path) else r.setDataSource(context, Uri.parse(uri))
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
            val tw = (h * w / vh.coerceAtLeast(1)).coerceAtLeast(16)
            return r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, h)
        } finally {
            runCatching { r.release() }
        }
    }
}
