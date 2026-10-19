package au.parmtech.tongcut

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Offline autosave/recovery acceptance, no real user media or network. */
@RunWith(AndroidJUnit4::class)
class ProjectRecoveryDeviceTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun layeredProject(): EditorDraft {
        var draft = EditorDraft(
            title="Recover P2 layers",
            clips=listOf(DraftClip("content://synthetic/main","Main",10_000)),
            exportAudioBitrateBps=192_000,
            exportMaxFrameRate=60
        )
        draft = LayerEdits.addText(draft, 1_000L, "Offline title")
        draft = LayerEdits.addAudio(draft,2_000L,"content://synthetic/audio",5_000L)
        draft = LayerEdits.addVisual(
            draft,3_000L,LayerKind.IMAGE,"content://synthetic/image",4_000L
        )
        val photo=draft.layers.layers.first { it.kind==LayerKind.IMAGE }
        draft = LayerEdits.setPhotoEffects(draft,photo.id,
            PhotoEffects(mask=PhotoMask.CIRCLE,
                chroma=PhotoChroma(tolerance=0.2f)))
        val audio=draft.layers.tracks.first { it.kind==LayerKind.AUDIO }
        draft = LayerEdits.setAudioGain(draft,audio.id,0.6f)
        return draft
    }

    @Test fun legacyAndMalformedAudioExportRatesDefaultSafely() {
        val id="p7-audio-export-rate-migration"
        val store=ProjectStore(app,id)
        store.delete()
        val jsonFile=File(File(app.filesDir,"tongcut-projects"),"$id.json")
        try {
            store.save(layeredProject())
            val legacy=JSONObject(jsonFile.readText())
                .put("schema",5)
                .apply { remove("exportAudioBitrateBps") }
            jsonFile.writeText(legacy.toString())
            assertEquals(128_000, ProjectStore(app,id).load()!!.exportAudioBitrateBps)

            legacy.put("schema",6).put("exportAudioBitrateBps",900_000)
            jsonFile.writeText(legacy.toString())
            assertEquals(128_000, ProjectStore(app,id).load()!!.exportAudioBitrateBps)
        } finally { store.delete() }
    }

    @Test fun projectSchemaSevenPersistsFrameRateAndLegacyDefaultsToThirty() {
        val id="p7-frame-rate-migration"
        val store=ProjectStore(app,id)
        store.delete()
        val jsonFile=File(File(app.filesDir,"tongcut-projects"),"$id.json")
        try {
            store.save(layeredProject())
            val current=JSONObject(jsonFile.readText())
            assertEquals(7,current.getInt("schema"))
            assertEquals(60,current.getInt("exportMaxFrameRate"))
            assertEquals(60,ProjectStore(app,id).load()!!.exportMaxFrameRate)

            current.put("schema",6).remove("exportMaxFrameRate")
            jsonFile.writeText(current.toString())
            assertEquals(ExportFrameRate.DEFAULT,
                ProjectStore(app,id).load()!!.exportMaxFrameRate)

            current.put("schema",7).put("exportMaxFrameRate",120)
            jsonFile.writeText(current.toString())
            assertEquals(ExportFrameRate.DEFAULT,
                ProjectStore(app,id).load()!!.exportMaxFrameRate)
        } finally { store.delete() }
    }

    @Test fun fullMultilayerDraftSurvivesStoreReopenEvenIfMediaHasMoved() {
        val id="p2device-recovery-roundtrip"
        val store=ProjectStore(app,id)
        store.delete()
        try {
            val expected=layeredProject()
            store.save(expected)
            val recovered=ProjectStore(app,id).load()
            assertEquals(expected,recovered)
            assertEquals("content://synthetic/image",
                recovered!!.layers.layers.first { it.kind==LayerKind.IMAGE }.sourceUri)
            val history=EditHistory(recovered)
            val changed=LayerEdits.changeText(recovered,
                recovered.layers.layers.first { it.kind==LayerKind.TEXT }.id,"Updated")
            history.apply(changed)
            assertEquals(recovered,history.undo())
            assertEquals(changed,history.redo())
        } finally { store.delete() }
    }

    @Test fun interruptedAtomicWriteCannotEraseLastCommittedProject() {
        val id="p2device-atomic-interrupted"
        val store=ProjectStore(app,id)
        store.delete()
        val json=File(File(app.filesDir,"tongcut-projects"),"$id.json")
        try {
            val initial=layeredProject()
            store.save(initial)
            val atomic=AtomicFile(json)
            val unfinished=atomic.startWrite()
            try {
                unfinished.write("{broken,uncommitted".toByteArray())
                unfinished.flush()
                // Equivalent to process termination BEFORE finishWrite is called.
                val restored=ProjectStore(app,id).load()
                assertEquals(initial,restored)
            } finally {
                atomic.failWrite(unfinished)
            }
        } finally { store.delete() }
    }
}
