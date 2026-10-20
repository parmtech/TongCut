package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * Shared source image -> premultiplied-alpha photo conversion used by editor preview
 * and Media3 export. Decode bounded by longest side to avoid huge 4K+ allocations.
 */
internal object PhotoBitmapDecoder {
    const val MAX_SIDE = 1024

    fun decode(
        context: Context, uri: Uri, effects: PhotoEffects,
        colorLutProfile: ColorLutProfile? = null
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // BitmapFactory.decodeStream RETURNS NULL intentionally when
        // inJustDecodeBounds=true. Only a missing input stream is an error.
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to open photo")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
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
        // Camera JPEGs may store rotation in EXIF rather than in the pixels.
        // Orient here once so *both* preview and Media3 export use the same source.
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        val transform = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.setScale(-1f, 1f); true
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.setRotate(180f); true
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f); true
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.setRotate(90f); true
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.setRotate(270f); true
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f); matrix.postScale(-1f, 1f); true
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f); matrix.postScale(-1f, 1f); true
            }
            else -> false
        }
        var bitmap = if (transform) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        if (effects.faceRetouch.isActive) {
            val source = bitmap
            bitmap = try {
                ManualFaceRetouchBitmapPreview.render(
                    source, effects.faceRetouch, maxPixels = MAX_SIDE * MAX_SIDE
                )
            } catch (failure: Throwable) {
                if (!source.isRecycled) source.recycle()
                throw failure
            }
            if (bitmap !== source && !source.isRecycled) source.recycle()
        }
        // Retouch is applied before alpha/matte work to avoid sampling across transparent edges.
        val lut = if (effects.applyColorLut) {
            requireNotNull(colorLutProfile) {
                "This layer uses a LUT that is no longer available in the project"
            }.copy(strength = effects.colorLutStrength)
        } else null
        if (effects.copy(faceRetouch = ManualFaceRetouch(), applyColorLut = false,
                colorLutStrength = 1f) == PhotoEffects() && lut == null) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        require(width.toLong() * height <= MAX_SIDE.toLong() * MAX_SIDE * 4) {
            "Photo exceeds allocated bitmap safety bound"
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // Apply the LUT before spatial effects and matte, preserving source alpha.
        lut?.let { profile ->
            pixels.indices.forEach { index ->
                pixels[index] = ColorLutOverlayTransform.applyArgb(pixels[index], profile)
            }
        }
        val blurSource = if (effects.blur > 0f || effects.sharpen > 0f) pixels.copyOf() else pixels
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                if (effects.blur > 0f) {
                    pixels[idx] = PhotoMatte.blurPixel(
                        blurSource, x, y, width, height, effects.blur
                    )
                }
                if (effects.sharpen > 0f) {
                    pixels[idx] = PhotoMatte.sharpenPixel(
                        blurSource, pixels[idx], x, y, width, height, effects.sharpen
                    )
                }
                if (effects.grain > 0f) {
                    pixels[idx] = PhotoMatte.grainPixel(pixels[idx], x, y, effects.grain)
                }
                pixels[idx] = PhotoMatte.applyAlpha(
                    pixels[idx], x, y, width, height, effects
                )
            }
        }
        val masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        masked.setPixels(pixels, 0, width, 0, 0, width, height)
        if (!bitmap.isRecycled) bitmap.recycle()
        return masked
    }
}
