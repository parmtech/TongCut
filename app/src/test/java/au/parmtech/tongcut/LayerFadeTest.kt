package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class LayerFadeTest {
    @Test fun fadeWorksAtBoundariesAndMiddle() {
        val f=LayerFade(inUs=1_000_000,outUs=2_000_000)
        val duration=5_000_000L
        assertEquals(0f,f.alphaAt(0L,duration),0f)
        assertEquals(0.5f,f.alphaAt(500_000,duration),0.0001f)
        assertEquals(1f,f.alphaAt(2_000_000,duration),0.0001f)
        assertEquals(0.5f,f.alphaAt(4_000_000,duration),0.0001f)
        assertEquals(0f,f.alphaAt(duration,duration),0f)
    }

    @Test fun noFadeIsAlwaysOpaque() {
        assertEquals(1f,LayerFade().alphaAt(0L,1_000_000),0f)
        assertEquals(1f,LayerFade().alphaAt(999_999L,1_000_000),0f)
    }

    @Test fun splitPreservesExactAlphaAcrossBoundary() {
        val original=LayerFade(1_000_000,2_000_000)
        val (left,right)=original.split(4_000_000,5_000_000)
        assertEquals(0.5f,left.alphaAt(4_000_000L,4_000_000L),0.0001f)
        assertEquals(0.5f,right.alphaAt(0L,1_000_000L),0.0001f)
        for(step in 0..100) {
            val leftAt=step.toLong()*40_000L
            assertEquals(original.alphaAt(leftAt,5_000_000L),
                left.alphaAt(leftAt,4_000_000L),0.0001f)
        }
        for(step in 0..100) {
            val rightAt=step.toLong()*10_000L
            assertEquals(original.alphaAt(4_000_000L+rightAt,5_000_000L),
                right.alphaAt(rightAt,1_000_000L),0.0001f)
        }
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectOverlappingFades() { LayerFade(600_000,600_000).validate(1_000_000) }
    @Test(expected=IllegalArgumentException::class)
    fun rejectNegativeFade() { LayerFade(-1000,0) }
    @Test fun resizeResetStartsFadeFromNewClipBounds() {
        val edited=LayerFade(100_000,200_000,500_000,1_000_000)
        val reset=edited.resetToClip()
        assertEquals(LayerFade(100_000,200_000),reset)
        assertEquals(0f,reset.alphaAt(0,500_000),0f)
    }
}
