package au.parmtech.tongcut

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min

/** Decodes one source trim to bounded PCM16 WAV, reversing interleaved sample frames. */
internal object ReverseAudioMaterializer {
    private const val MAX_DURATION_US = 10 * 60 * 1_000_000L
    private const val MAX_PCM_BYTES = 256L * 1024L * 1024L
    private const val MAX_SAMPLE_RATE = 192_000
    private const val MAX_CHANNELS = 8
    private const val POLL_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 6_000
    private const val WAV_HEADER_BYTES = 44
    private const val STALE_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1_000

    fun materialize(
        context: Context,
        source: Uri,
        sourceInUs: Long,
        sourceOutUs: Long,
        targetDurationUs: Long,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): File? {
        require(sourceInUs >= 0L && sourceOutUs > sourceInUs)
        require(targetDurationUs > 0L && targetDurationUs <= MAX_DURATION_US) {
            "Reverse audio exceeds the 10-minute limit"
        }
        val root = File(context.applicationContext.cacheDir, "tongcut-reverse-audio")
        require(root.isDirectory || root.mkdirs()) { "Cannot create reverse audio cache" }
        val staleBeforeMs = System.currentTimeMillis() - STALE_CACHE_AGE_MS
        root.listFiles()?.filter {
            it.isFile && it.name.substringBefore('.').let { name ->
                runCatching { UUID.fromString(name) }.isSuccess
            } && it.lastModified() < staleBeforeMs
        }
            ?.forEach { it.delete() }
        val id = UUID.randomUUID().toString()
        val pcmFile = File(root, "$id.pcm.part")
        val wavPart = File(root, "$id.wav.part")
        val wavFile = File(root, "$id.wav")
        var decoder: MediaCodec? = null
        val extractor = MediaExtractor()
        try {
            checkCancelled(isCancelled)
            extractor.setDataSource(context.applicationContext, source, emptyMap())
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            extractor.seekTo(sourceInUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val mediaCodec = MediaCodec.createDecoderByType(mime)
            decoder = mediaCodec
            mediaCodec.configure(inputFormat, null, null, 0)
            mediaCodec.start()

            var sampleRate = 0
            var channels = 0
            var frameBytes = 0
            var targetFrames = 0L
            var pcmBytes = 0L
            var inputDone = false
            var outputDone = false
            var outputFormatSet = false
            var idlePolls = 0
            var wroteFrames = 0L
            val coveredFrames = BitSet()
            val info = MediaCodec.BufferInfo()

            RandomAccessFile(pcmFile, "rw").use { pcm ->
                while (!outputDone) {
                    checkCancelled(isCancelled)
                    var madeProgress = false
                    if (!inputDone) {
                        val inputIndex = mediaCodec.dequeueInputBuffer(POLL_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val input = requireNotNull(mediaCodec.getInputBuffer(inputIndex))
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            val ptsUs = if (size < 0) Long.MAX_VALUE else extractor.sampleTime
                            if (size < 0 || ptsUs >= sourceOutUs) {
                                mediaCodec.queueInputBuffer(
                                    inputIndex, 0, 0,
                                    if (ptsUs == Long.MAX_VALUE) 0L else ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val sampleFlags = extractor.sampleFlags
                                require((sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) == 0) {
                                    "Encrypted source audio cannot be reversed"
                                }
                                require((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) {
                                    "Partial source audio frames cannot be reversed"
                                }
                                mediaCodec.queueInputBuffer(
                                    inputIndex, 0, size, ptsUs, sampleFlags
                                )
                                extractor.advance()
                            }
                            madeProgress = true
                        }
                    }

                    when (val outputIndex = mediaCodec.dequeueOutputBuffer(info, POLL_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = mediaCodec.outputFormat
                            val nextSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val nextChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else android.media.AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) {
                                "This device decoder returned non-PCM16 audio"
                            }
                            require(nextSampleRate in 8_000..MAX_SAMPLE_RATE &&
                                nextChannels in 1..MAX_CHANNELS) {
                                "Audio sample format exceeds reverse limits"
                            }
                            if (outputFormatSet) {
                                require(nextSampleRate == sampleRate && nextChannels == channels) {
                                    "Audio decoder changed its PCM format mid-stream"
                                }
                            } else {
                                sampleRate = nextSampleRate
                                channels = nextChannels
                                outputFormatSet = true
                            }
                            frameBytes = Math.multiplyExact(channels, 2)
                            targetFrames = Math.addExact(
                                Math.multiplyExact(targetDurationUs, sampleRate.toLong()), 500_000L
                            ) / 1_000_000L
                            pcmBytes = Math.multiplyExact(targetFrames, frameBytes.toLong())
                            require(pcmBytes in 1..MAX_PCM_BYTES) {
                                "Decoded reverse audio exceeds the 256 MB cache limit"
                            }
                            if (outputFormatSet && pcm.length() == 0L) pcm.setLength(pcmBytes)
                            madeProgress = true
                        }
                        else -> if (outputIndex >= 0) {
                            try {
                                if (info.size > 0) {
                                    require(sampleRate > 0 && frameBytes > 0) {
                                        "Decoder produced PCM before its output format"
                                    }
                                    require(info.size % frameBytes == 0) {
                                        "Decoder returned a partial interleaved sample frame"
                                    }
                                    val decodedFrames = info.size / frameBytes
                                    val ptsDeltaUs = Math.subtractExact(
                                        info.presentationTimeUs, sourceInUs
                                    )
                                    val firstInTrim = if (ptsDeltaUs >= 0L) 0L else {
                                        val beforeTrim = Math.negateExact(ptsDeltaUs)
                                        Math.addExact(Math.multiplyExact(beforeTrim, sampleRate.toLong()),
                                            999_999L) / 1_000_000L
                                    }
                                    val firstInTrimInt = firstInTrim.coerceIn(0L, decodedFrames.toLong()).toInt()
                                    val firstTarget = max(0L, Math.addExact(
                                        Math.multiplyExact(ptsDeltaUs, sampleRate.toLong()),
                                        500_000L
                                    ) / 1_000_000L)
                                    val available = decodedFrames - firstInTrimInt
                                    val writable = min(available.toLong(), targetFrames - firstTarget)
                                        .coerceAtLeast(0L).toInt()
                                    if (writable > 0) {
                                        val buffer = requireNotNull(mediaCodec.getOutputBuffer(outputIndex))
                                            .duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                        val startByte = info.offset + firstInTrimInt * frameBytes
                                        buffer.position(startByte)
                                        buffer.limit(startByte + writable * frameBytes)
                                        val bytes = ByteArray(writable * frameBytes)
                                        buffer.get(bytes)
                                        pcm.seek(Math.multiplyExact(firstTarget, frameBytes.toLong()))
                                        pcm.write(bytes)
                                        coveredFrames.set(firstTarget.toInt(),
                                            Math.addExact(firstTarget, writable.toLong()).toInt())
                                        wroteFrames = Math.addExact(wroteFrames, writable.toLong())
                                    }
                                }
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            } finally {
                                mediaCodec.releaseOutputBuffer(outputIndex, false)
                            }
                            madeProgress = true
                        }
                    }
                    idlePolls = if (madeProgress) 0 else idlePolls + 1
                    check(idlePolls <= MAX_IDLE_POLLS) { "Audio decoder stopped making progress" }
                }
                pcm.fd.sync()
            }
            require(wroteFrames > 0L && sampleRate > 0 && frameBytes > 0) {
                "No audio samples were decoded from the selected trim"
            }
            require(coveredFrames.nextClearBit(0).toLong() >= targetFrames) {
                "Decoded audio does not cover the full selected trim"
            }
            checkCancelled(isCancelled)
            writeReversedWave(
                pcmFile, wavPart, wavFile, sampleRate, channels, targetFrames, frameBytes,
                isCancelled
            )
            return wavFile
        } catch (failure: Throwable) {
            wavFile.delete()
            wavPart.delete()
            throw failure
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            pcmFile.delete()
        }
    }

    private fun writeReversedWave(
        pcmFile: File,
        wavPart: File,
        wavFile: File,
        sampleRate: Int,
        channels: Int,
        frames: Long,
        frameBytes: Int,
        isCancelled: () -> Boolean
    ) {
        val dataBytes = Math.multiplyExact(frames, frameBytes.toLong())
        require(dataBytes <= 0xffff_ffffL - WAV_HEADER_BYTES)
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataBytes).toInt())
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(Math.multiplyExact(sampleRate, frameBytes))
        header.putShort(frameBytes.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())

        FileInputStream(pcmFile).use { input ->
            FileOutputStream(wavPart).use { output ->
                output.write(header.array())
                val maxChunkFrames = (1024 * 1024 / frameBytes).coerceAtLeast(1)
                var remaining = frames
                while (remaining > 0L) {
                    checkCancelled(isCancelled)
                    val chunkFrames = min(remaining, maxChunkFrames.toLong()).toInt()
                    val chunkBytes = Math.multiplyExact(chunkFrames, frameBytes)
                    val startFrame = remaining - chunkFrames
                    input.channel.position(Math.multiplyExact(startFrame, frameBytes.toLong()))
                    val block = ByteArray(chunkBytes)
                    var offset = 0
                    while (offset < block.size) {
                        val count = input.read(block, offset, block.size - offset)
                        check(count > 0) { "Decoded PCM cache ended unexpectedly" }
                        offset += count
                    }
                    reverseFrames(block, chunkFrames, frameBytes)
                    output.write(block)
                    remaining = startFrame
                }
                output.fd.sync()
            }
        }
        check(wavPart.renameTo(wavFile)) { "Cannot publish reversed audio cache" }
    }

    private fun reverseFrames(bytes: ByteArray, frames: Int, frameBytes: Int) {
        var left = 0
        var right = frames - 1
        val temp = ByteArray(frameBytes)
        while (left < right) {
            val leftByte = left * frameBytes
            val rightByte = right * frameBytes
            System.arraycopy(bytes, leftByte, temp, 0, frameBytes)
            System.arraycopy(bytes, rightByte, bytes, leftByte, frameBytes)
            System.arraycopy(temp, 0, bytes, rightByte, frameBytes)
            left++
            right--
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw java.util.concurrent.CancellationException(
            "Reverse audio preparation cancelled"
        )
    }
}
