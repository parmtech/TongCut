package au.parmtech.tongcut

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut

/** Builds a fresh Media3 LUT because each GL effect owns and releases its texture. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ColorLutProfile.toMedia3Effect(): SingleColorLut =
    SingleColorLut.createFromCube(toCube())
