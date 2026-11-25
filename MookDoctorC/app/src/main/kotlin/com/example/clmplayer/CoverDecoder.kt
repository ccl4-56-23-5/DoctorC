package com.example.clmplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal object CoverDecoder {
    fun decode(data: ByteArray, maxSide: Int = 1200): Bitmap? = runCatching {
        if (data.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        val longest = maxOf(sampled.width, sampled.height)
        if (longest <= maxSide) return sampled
        val width = ((sampled.width.toLong() * maxSide + longest / 2) / longest).toInt().coerceAtLeast(1)
        val height = ((sampled.height.toLong() * maxSide + longest / 2) / longest).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(sampled, width, height, true).also { sampled.recycle() }
    }.getOrNull()
}
