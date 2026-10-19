package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class VideoMatteProjectTest {
    private fun draft() = LayerEdits.addVisual(
        EditorDraft(clips=listOf(
            DraftClip("content://base", "Base",durationMs=6_000L)
        )), 500L, LayerKind.VIDEO, "content://green-screen", 4_000L
    )

    @Test fun videoMatteIsSavedAndExportEffectIsUndoable() {
        val raw=draft()
        val layer=raw.layers.layers.single()
        val newEffects=PhotoEffects(
            mask=PhotoMask.CIRCLE,feather=0.08f,
            chroma=PhotoChroma(0xFF00FF00.toInt(),0.2f,0.1f)
        )
        val edited=LayerEdits.setVideoEffects(raw,layer.id,newEffects)
        assertEquals(PhotoEffects(),raw.layers.layers.single().photoEffects)
        assertEquals(newEffects,edited.layers.layers.single().photoEffects)
        assertEquals(edited.layers,
            LayerTimelineCodec.decode(LayerTimelineCodec.encode(edited.layers)))
        val history=EditHistory(raw)
        history.apply(edited)
        assertEquals(raw,history.undo())
        assertEquals(edited,history.redo())
    }

    @Test fun rejectsNonVideoAndLockedTrackMattes() {
        val video=draft()
        val layer=video.layers.layers.single()
        val locked=LayerEdits.setTrackLocked(video,layer.trackId,true)
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.setVideoEffects(locked,layer.id,PhotoEffects(PhotoMask.CIRCLE))
        }
        val photo=LayerEdits.addVisual(
            EditorDraft(clips=listOf(DraftClip(
                "content://main","Video",durationMs=3_000L
            ))),0L,LayerKind.IMAGE,"content://photo",1_000L
        )
        assertThrows(IllegalArgumentException::class.java) {
            LayerEdits.setVideoEffects(
                photo,photo.layers.layers.single().id,PhotoEffects(PhotoMask.CIRCLE)
            )
        }
    }

    @Test fun duplicateRetainsVideoMatte() {
        val input=draft()
        val id=input.layers.layers.single().id
        val matte=LayerEdits.setVideoEffects(
            input,id,PhotoEffects(chroma=PhotoChroma())
        )
        val duplicated=AdvancedClipEdits.duplicateAt(matte,id,1_000L)
        assertEquals(2,duplicated.layers.layers.size)
        assertTrue(duplicated.layers.layers.all {
            it.photoEffects.chroma != null
        })
    }
}
