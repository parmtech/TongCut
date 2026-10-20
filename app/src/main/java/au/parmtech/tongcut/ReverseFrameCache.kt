package au.parmtech.tongcut

import java.io.File
import java.io.Closeable
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Private, bounded disk cache for individually encoded reverse frames. */
class ReverseFrameCache(
    cacheRoot: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    private val directory = File(cacheRoot, DIRECTORY_NAME)

    init {
        require(maxBytes > 0L)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create reverse-frame cache")
        }
        synchronized(lock) { removeAbandonedTemps() }
    }

    /** A pinned cache entry. Close it after decoding so eviction can reclaim it. */
    inner class FrameLease internal constructor(val file: File) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                val key = file.canonicalPath
                val remaining = pinnedFiles[key]?.minus(1) ?: return@synchronized
                if (remaining == 0) pinnedFiles.remove(key) else pinnedFiles[key] = remaining
            }
        }
    }

    /** Returns a pinned cache file, or null when the requested frame is not cached. */
    fun openFrame(segmentKey: String, frameIndex: Long): FrameLease? = synchronized(lock) {
        require(frameIndex >= 0L)
        val file = frameFile(segmentKey, frameIndex)
        if (!file.isFile) null else {
            file.setLastModified(System.currentTimeMillis())
            val key = file.canonicalPath
            pinnedFiles[key] = (pinnedFiles[key] ?: 0) + 1
            FrameLease(file)
        }
    }

    /**
     * Atomically stores one encoded frame. Cancellation is checked between bounded
     * chunks; a cancelled write deletes only its temporary file and preserves any
     * previous complete entry for the same key.
     */
    fun put(
        segmentKey: String,
        frameIndex: Long,
        encodedFrame: ByteArray,
        isCancelled: () -> Boolean = { false }
    ): File = synchronized(lock) {
        require(frameIndex >= 0L)
        require(segmentKey.isNotBlank())
        require(encodedFrame.isNotEmpty())
        require(encodedFrame.size.toLong() <= maxBytes) { "Frame exceeds cache size limit" }
        require(encodedFrame.size <= MAX_FRAME_BYTES) { "Frame exceeds per-frame cache limit" }
        if (isCancelled()) throw CancellationException("Reverse frame cache write cancelled")
        val destination = frameFile(segmentKey, frameIndex)
        val temp = File.createTempFile(".tmp-", ".frame", directory)
        try {
            FileOutputStream(temp).use { output ->
                var offset = 0
                while (offset < encodedFrame.size) {
                    if (isCancelled()) throw CancellationException("Reverse frame cache write cancelled")
                    val count = minOf(IO_CHUNK_BYTES, encodedFrame.size - offset)
                    output.write(encodedFrame, offset, count)
                    offset += count
                }
                output.fd.sync()
            }
            if (isCancelled()) throw CancellationException("Reverse frame cache write cancelled")
            check(!isPinned(destination)) { "Cannot replace a reverse frame while it is in use" }
            evictToFit(encodedFrame.size.toLong(), destination, temp)
            if (isCancelled()) throw CancellationException("Reverse frame cache write cancelled")
            moveAtomically(temp, destination)
            destination
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }

    fun removeSegment(segmentKey: String) = synchronized(lock) {
        val prefix = segmentPrefix(segmentKey)
        directory.listFiles()?.filter { it.name.startsWith(prefix) && !isPinned(it) }
            ?.forEach(File::delete)
        Unit
    }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.filterNot(::isPinned)?.forEach(File::delete)
        Unit
    }

    private fun frameFile(segmentKey: String, frameIndex: Long): File {
        require(frameIndex >= 0L)
        val child = File(directory, "${segmentPrefix(segmentKey)}$frameIndex.frame")
        require(child.canonicalFile.parentFile == directory.canonicalFile) {
            "Reverse frame cache path escaped its private directory"
        }
        return child
    }

    private fun segmentPrefix(segmentKey: String): String =
        digest(segmentKey.toByteArray(Charsets.UTF_8)) + "-"

    private fun evictToFit(incomingBytes: Long, replacing: File, staged: File) {
        val files = directory.listFiles()?.filter {
            it.isFile && it != replacing && it != staged && !it.name.startsWith(".tmp-")
        } ?: emptyList()
        var total = files.sumOf { it.length() }
        val ordered = files.filterNot(::isPinned)
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
        for (file in ordered) {
            if (total <= maxBytes - incomingBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        if (total > maxBytes - incomingBytes) {
            throw IllegalStateException("Reverse frame cache cannot reclaim enough disk space")
        }
    }

    private fun removeAbandonedTemps() {
        directory.listFiles()?.filter { it.name.startsWith(".tmp-") }?.forEach(File::delete)
    }

    private fun moveAtomically(source: File, target: File) {
        // Fail closed when the filesystem cannot provide an atomic same-volume rename.
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun isPinned(file: File): Boolean = pinnedFiles.containsKey(file.canonicalPath)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024L * 1024L
        private const val DIRECTORY_NAME = "tongcut-reverse-frames-v1"
        private const val IO_CHUNK_BYTES = 64 * 1024
        private const val MAX_FRAME_BYTES = 64 * 1024 * 1024
        private val lock = Any()
        private val pinnedFiles = mutableMapOf<String, Int>()
    }
}

