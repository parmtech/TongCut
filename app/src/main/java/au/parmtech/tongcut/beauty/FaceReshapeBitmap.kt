package au.parmtech.tongcut.beauty

import android.graphics.Bitmap

/**
 * Android Bitmap bridge for the deterministic CPU still-image prototype.
 *
 * Use only with decoded, correctly oriented input photos on a background
 * dispatcher. It does not run face landmark detection or process video tracks.
 * The input Bitmap remains caller-owned and is never mutated/recycled.
 */
object FaceReshapeBitmap {
    fun render(
        input: Bitmap,
        landmarks: FaceReshapeLandmarks,
        params: FaceReshapeParams
    ): Bitmap {
        require(!input.isRecycled) { "Image was recycled" }
        val width = input.width
        val height = input.height
        require(width >= 2 && height >= 2 &&
            width.toLong() * height <= FaceReshapeWarp.MAX_PIXELS) {
            "Photo is oversized or has invalid dimensions"
        }

        val source = IntArray(width * height)
        try {
            input.getPixels(source, 0, width, 0, 0, width, height)
        } catch (cause: RuntimeException) {
            throw IllegalArgumentException(
                "Face reshape needs a CPU-readable, software Bitmap (not HARDWARE)",
                cause
            )
        }

        val warped = FaceReshapeWarp.renderArgb(
            source, width, height, landmarks, params
        )
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(warped, 0, width, 0, 0, width, height)
        return output
    }
}
