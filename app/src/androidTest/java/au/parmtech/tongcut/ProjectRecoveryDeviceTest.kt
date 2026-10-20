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
            exportMaxFrameRate=60,
            primaryBlur=0.03f,
            primarySharpen=0.4f,
            primaryGrain=0.6f,
            primaryVignette=0.7f,
            primaryQuarterTurns=1,
            primaryFlipHorizontal=true,
            colorLutProfile=ColorLutProfile("Recovery LUT",2,List(8) { 0xff808080.toInt() },0.6f)
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

    @Test fun projectSchemaFourteenPersistsPrimaryAdjustmentsAndLegacyDefaultsSafely() {
        val id="p7-frame-rate-migration"
        val store=ProjectStore(app,id)
        store.delete()
        val jsonFile=File(File(app.filesDir,"tongcut-projects"),"$id.json")
        try {
            store.save(layeredProject())
            val current=JSONObject(jsonFile.readText())
            assertEquals(14,current.getInt("schema"))
            assertEquals(60,current.getInt("exportMaxFrameRate"))
            val loaded=ProjectStore(app,id).load()!!
            assertEquals(60,loaded.exportMaxFrameRate)
            assertEquals(layeredProject().colorLutProfile,loaded.colorLutProfile)
            assertEquals(0.03f,loaded.primaryBlur,0f)
            assertEquals(0.4f,loaded.primarySharpen,0f)
            assertEquals(0.6f,loaded.primaryGrain,0f)
            assertEquals(0.7f,loaded.primaryVignette,0f)
            assertEquals(1,loaded.primaryQuarterTurns)
            assertTrue(loaded.primaryFlipHorizontal)

            val schemaThirteen=JSONObject(current.toString()).apply {
                put("schema",13)
                remove("primaryVignette")
            }
            jsonFile.writeText(schemaThirteen.toString())
            val schemaThirteenDraft=ProjectStore(app,id).load()!!
            assertEquals(0.6f,schemaThirteenDraft.primaryGrain,0f)
            assertEquals(0f,schemaThirteenDraft.primaryVignette,0f)

            val schemaTwelve=JSONObject(current.toString()).apply {
                put("schema",12)
                remove("primaryGrain")
            }
            jsonFile.writeText(schemaTwelve.toString())
            val schemaTwelveDraft=ProjectStore(app,id).load()!!
            assertEquals(0.03f,schemaTwelveDraft.primaryBlur,0f)
            assertEquals(0.4f,schemaTwelveDraft.primarySharpen,0f)
            assertEquals(0f,schemaTwelveDraft.primaryGrain,0f)
            assertEquals(0f,schemaTwelveDraft.primaryVignette,0f)

            current.put("schema",6).remove("exportMaxFrameRate")
            jsonFile.writeText(current.toString())
            assertEquals(ExportFrameRate.DEFAULT,
                ProjectStore(app,id).load()!!.exportMaxFrameRate)
            assertEquals(0f,ProjectStore(app,id).load()!!.primaryBlur,0f)
            assertEquals(0f,ProjectStore(app,id).load()!!.primarySharpen,0f)
            assertEquals(0f,ProjectStore(app,id).load()!!.primaryGrain,0f)
            assertEquals(0f,ProjectStore(app,id).load()!!.primaryVignette,0f)
            assertNull(ProjectStore(app,id).load()!!.colorLutProfile)

            current.put("schema",7).put("exportMaxFrameRate",120)
            jsonFile.writeText(current.toString())
            assertEquals(ExportFrameRate.DEFAULT,
                ProjectStore(app,id).load()!!.exportMaxFrameRate)
            assertNull(ProjectStore(app,id).load()!!.colorLutProfile)
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
