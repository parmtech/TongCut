package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class ChromaSampleMathTest {
    @Test fun sourceCoordinatesClampToValidPixelRange() {
        assertEquals(0 to 0,
            ChromaSampleMath.pixelAt(0f, 0f, 640, 360, 512, 288))
        assertEquals(511 to 287,
            ChromaSampleMath.pixelAt(640f, 360f, 640, 360, 512, 288))
        assertEquals(511 to 287,
            ChromaSampleMath.pixelAt(6_400f, 3_600f, 640, 360, 512, 288))
        assertEquals(0 to 0,
            ChromaSampleMath.pixelAt(-80f, -90f, 640, 360, 512, 288))
    }

    @Test fun sourceTapMapsToTheActualDecodedFrame() {
        assertEquals(256 to 144,
            ChromaSampleMath.pixelAt(320f, 180f, 640, 360, 512, 288))
        assertEquals(100 to 72,
            ChromaSampleMath.pixelAt(125f, 90f, 640, 360, 512, 288))
        assertEquals(10 to 79,
            ChromaSampleMath.pixelAt(12.5f, 100f, 100, 100, 80, 80))
    }

    @Test fun samplingCustomBlueGreyKeepsTheRgbAndForcesOpaqueKey() {
        val partiallyTransparent = 0x45243A7C
        val opaque = ChromaSampleMath.opaqueArgb(partiallyTransparent)
        assertEquals(0xFF243A7C.toInt(), opaque)
        assertEquals(0xFF0000FF.toInt(),
            ChromaSampleMath.opaqueArgb(0x000000FF))
    }

    @Test fun invalidTapCoordinatesAndDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSampleMath.pixelAt(Float.NaN, 10f, 640, 360, 512, 288)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSampleMath.pixelAt(10f, Float.POSITIVE_INFINITY, 640, 360, 512, 288)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSampleMath.pixelAt(5f, 7f, 0, 360, 512, 288)
        }
    }
}
