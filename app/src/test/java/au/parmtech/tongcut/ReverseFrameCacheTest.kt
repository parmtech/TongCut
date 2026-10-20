package au.parmtech.tongcut

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReverseFrameCacheTest {
    private lateinit var root: File

    @Before fun createRoot() { root = Files.createTempDirectory("tongcut-reverse-test").toFile() }
    @After fun removeRoot() { root.deleteRecursively() }

    @Test fun writesAtomicallyAndKeysDoNotExposeSourceUris() {
        val cache = ReverseFrameCache(root, 1024)
        val uri = "content://private-owner-video/secret-name"
        val saved = cache.put(uri, 3, byteArrayOf(1, 2, 3))
        cache.openFrame(uri, 3)!!.use { assertArrayEquals(byteArrayOf(1, 2, 3), it.file.readBytes()) }
        assertFalse(saved.name.contains("private-owner-video"))
        assertFalse(saved.name.contains("secret-name"))
        assertNull(cache.openFrame(uri, 2))
        assertEquals(1, saved.parentFile!!.listFiles()!!.size)
    }

    @Test fun evictsLeastRecentlyUsedEntriesToStayWithinDiskBudget() {
        val cache = ReverseFrameCache(root, 6)
        val first = cache.put("first", 0, byteArrayOf(1, 1, 1))
        val second = cache.put("second", 0, byteArrayOf(2, 2, 2))
        second.setLastModified(1L)
        cache.openFrame("first", 0)!!.close() // Make the first entry most recently used.
        cache.put("third", 0, byteArrayOf(3, 3, 3))
        assertTrue(first.exists())
        assertFalse(second.exists())
        cache.openFrame("third", 0)!!.close()
        assertTrue(first.parentFile!!.listFiles()!!.sumOf { it.length() } <= 6L)
    }

    @Test fun cancellationKeepsOldEntryAndLeavesNoTemporaryFile() {
        val cache = ReverseFrameCache(root, 1024 * 1024)
        val old = byteArrayOf(7, 8, 9)
        cache.put("clip", 4, old)
        var checks = 0
        try {
            cache.put("clip", 4, ByteArray(150_000) { 2 }) { ++checks >= 2 }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        cache.openFrame("clip", 4)!!.use { assertArrayEquals(old, it.file.readBytes()) }
        assertTrue(root.resolve("tongcut-reverse-frames-v1").listFiles()!!
            .none { it.name.startsWith(".tmp-") })
    }

    @Test fun removesOneSegmentOrClearsAllEntries() {
        val cache = ReverseFrameCache(root, 1024)
        cache.put("one", 0, byteArrayOf(1))
        cache.put("one", 1, byteArrayOf(2))
        cache.put("two", 0, byteArrayOf(3))
        cache.removeSegment("one")
        assertNull(cache.openFrame("one", 0))
        cache.openFrame("two", 0)!!.close()
        cache.clear()
        assertNull(cache.openFrame("two", 0))
    }

    @Test fun pinnedEntriesCannotBeEvictedWhileDecoderReadsThem() {
        val cache = ReverseFrameCache(root, 6)
        cache.put("first", 0, byteArrayOf(1, 1, 1))
        cache.put("second", 0, byteArrayOf(2, 2, 2))
        val first = cache.openFrame("first", 0)!!
        val second = cache.openFrame("second", 0)!!
        try {
            try {
                cache.put("third", 0, byteArrayOf(3, 3, 3))
                fail("Expected all-pinned capacity failure")
            } catch (_: IllegalStateException) { }
            assertTrue(first.file.exists())
            assertTrue(second.file.exists())
            assertNull(cache.openFrame("third", 0))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun clearLeavesPinnedFrameReadableUntilLeaseCloses() {
        val cache = ReverseFrameCache(root, 10)
        cache.put("clip", 0, byteArrayOf(4, 5, 6))
        val lease = cache.openFrame("clip", 0)!!
        cache.clear()
        assertTrue(lease.file.exists())
        assertArrayEquals(byteArrayOf(4, 5, 6), lease.file.readBytes())
        lease.close()
        cache.clear()
        assertFalse(lease.file.exists())
    }

    @Test fun segmentRemovalAndReplacementRespectActiveLease() {
        val cache = ReverseFrameCache(root, 10)
        val original = byteArrayOf(1, 2, 3)
        cache.put("clip", 2, original)
        val lease = cache.openFrame("clip", 2)!!
        try {
            cache.removeSegment("clip")
            assertTrue(lease.file.exists())
            try {
                cache.put("clip", 2, byteArrayOf(4, 5, 6))
                fail("Expected pinned replacement to fail")
            } catch (_: IllegalStateException) { }
            assertArrayEquals(original, lease.file.readBytes())
        } finally {
            lease.close()
        }
        cache.removeSegment("clip")
        assertFalse(lease.file.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFramesLargerThanConfiguredCache() {
        ReverseFrameCache(root, 2).put("clip", 0, byteArrayOf(1, 2, 3))
    }
}

