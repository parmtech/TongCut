package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** These are navigation-contract checks, not proof of visual/device acceptance. */
class ClipActionPolicyTest {
    @Test fun videoAndPhotoProvideRealVisualEditingEntryPoints() {
        val video = ClipActionPolicy.forKind(LayerKind.VIDEO)
        val photo = ClipActionPolicy.forKind(LayerKind.IMAGE)
        assertEquals(video, photo)
        assertTrue(ClipAction.KEYFRAME in video)
        assertTrue(ClipAction.SPLIT in video)
        assertTrue(ClipAction.DUPLICATE in video)
        assertTrue(ClipAction.DELETE in video)
        assertFalse(ClipAction.MUTE in video)
    }

    @Test fun audioOffersMuteWithoutVisualMotion() {
        val audio = ClipActionPolicy.forKind(LayerKind.AUDIO)
        assertTrue(ClipAction.MUTE in audio)
        assertTrue(ClipAction.SPLIT in audio)
        assertTrue(ClipAction.TRIM_START in audio)
        assertFalse(ClipAction.KEYFRAME in audio)
    }

    @Test fun unsupportedStickersExposeOnlySafeBackAction() {
        assertEquals(listOf(ClipAction.BACK), ClipActionPolicy.forKind(LayerKind.STICKER))
    }

    @Test fun commandsAreUniqueAndNavigable() {
        for (kind in LayerKind.entries) {
            val commands = ClipActionPolicy.forKind(kind)
            assertEquals(commands.size, commands.toSet().size)
            assertEquals(ClipAction.BACK, commands.first())
        }
    }
}
