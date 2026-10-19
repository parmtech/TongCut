package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Shared source image -> premultiplied-alpha photo conversion used by editor preview
 * and Media3 export. Decode bounded by longest side to avoid huge 4K+ allocations.
 */
internal object PhotoBitmapDecoder {
    const val MAX_SIDE = 1024

    fun decode(context: Context, uri: Uri, effects: PhotoEffects): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalArgumentException("Unable to open photo")
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        require(longest > 0) { "Unsupported image or missing dimensions" }
        var sample = 1
        while ((longest.toLong() + sample - 1) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalArgumentException("Unable to decode source photo")
        if (effects == PhotoEffects()) return decoded

        val width = decoded.width
        val height = decoded.height
        require(width.toLong() * height <= MAX_SIDE.toLong() * MAX_SIDE * 4) {
            "Photo exceeds allocated bitmap safety bound"
        }
        val pixels = IntArray(width * height)
        decoded.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                pixels[idx] = PhotoMatte.applyAlpha(
                    pixels[idx], x, y, width, height, effects
                )
            }
        }
        val masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        masked.setPixels(pixels, 0, width, 0, 0, width, height)
        if (!decoded.isRecycled) decoded.recycle()
        return masked
    }
}
