package com.filtercam.video

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the filtered frames that the GL renderer draws into [start]'s returned Surface, plus microphone audio,
 * into Movies/FilterCam via MediaStore. Frame and audio timestamps both come from System.nanoTime().
 * start() and stop() may be called from any thread; stop() blocks until the file is finalized, so call it off the main thread.
 *
 * Shutdown order (no thread outlives stop()): signal end of stream, join the worker threads (interrupting any that
 * are slow), only then close the muxer and release the codecs. Workers use their own references and treat a stopped
 * codec as "finish quietly", so a late worker can never crash on a released codec or write to a stopped muxer.
 */
class VideoRecorder(private val context: Context) {
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var sync: MuxSync? = null
    private var videoEnc: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioEnc: MediaCodec? = null
    private var audioRec: AudioRecord? = null
    @Volatile private var videoThread: Thread? = null
    @Volatile private var audioThread: Thread? = null
    private val stopping = AtomicBoolean(false)
    @Volatile private var stopRequested = false
    @Volatile private var stopAtMs = 0L
    @Volatile private var baseUs = 0L
    @Volatile var isRecording = false
        private set
    @Volatile var hasAudio = false
        private set
    /** Set when the pipeline fails while recording; the screen watches it and stops the recording. */
    @Volatile var failure: String? = null
        private set
    /** Actual video size in use (the requested size adjusted to what the encoder supports). */
    @Volatile var outWidth = 0
        private set
    @Volatile var outHeight = 0
        private set

    /** Starts the encoders and returns the surface the renderer must draw into. Throws if anything fails (nothing is left behind). */
    @SuppressLint("MissingPermission")
    fun start(width: Int, height: Int, withAudio: Boolean, orientationHint: Int = 0): Surface {
        check(!isRecording) { "Already recording" }
        check(!stopping.get()) { "Still finishing the previous recording" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FilterCam_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FilterCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }
        uri = u
        try {
            pfd = checkNotNull(resolver.openFileDescriptor(u, "rw"))
            val mx = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            mx.setOrientationHint(orientationHint)

            val venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEnc = venc
            val (w, h) = fitToEncoder(venc, width, height)
            outWidth = w; outHeight = h
            val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 8L).coerceIn(4_000_000L, 20_000_000L).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            venc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inSurface = venc.createInputSurface()
            inputSurface = inSurface
            venc.start()

            val audioOk = withAudio && runCatching { setupAudio() }.getOrDefault(false)
            hasAudio = audioOk
            val mux = MuxSync(mx, if (audioOk) 2 else 1) { failure = it }
            sync = mux

            baseUs = System.nanoTime() / 1000
            stopRequested = false
            failure = null
            isRecording = true
            videoThread = Thread({ drain(venc, mux) }, "rec-video").also { it.start() }
            if (audioOk) {
                val rec = audioRec!!
                val aenc = audioEnc!!
                audioThread = Thread({ audioLoop(rec, aenc, mux) }, "rec-audio").also { it.start() }
            }
            return inSurface
        } catch (t: Throwable) {
            cleanupFailed()
            throw t
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudio(): Boolean {
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        var aenc: MediaCodec? = null
        try {
            aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aenc.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aenc.start()
        } catch (t: Throwable) {
            runCatching { aenc?.release() }
            runCatching { rec.release() }
            throw t
        }
        audioRec = rec
        audioEnc = aenc
        return true
    }

    /** The nearest size the AVC encoder really supports (inside its limits and alignment), keeping the aspect ratio. */
    private fun fitToEncoder(enc: MediaCodec, w: Int, h: Int): Pair<Int, Int> {
        val caps = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: return w to h
        val wa = caps.widthAlignment.coerceAtLeast(1)
        val ha = caps.heightAlignment.coerceAtLeast(1)
        var cw = (w / wa) * wa
        var ch = (h / ha) * ha
        var guard = 0
        while (!caps.isSizeSupported(cw, ch) && guard++ < 40) {
            cw = ((cw * 0.9f).toInt() / wa) * wa
            ch = ((ch * 0.9f).toInt() / ha) * ha
            if (cw < 16 || ch < 16) break
        }
        check(cw >= 16 && ch >= 16 && caps.isSizeSupported(cw, ch)) { "This phone cannot record at that frame size" }
        return cw to ch
    }

    /** Waits for [t]; if it does not finish in time it is interrupted and waited for again, so no thread outlives stop(). */
    private fun joinOrInterrupt(t: Thread?, ms: Long) {
        if (t == null) return
        try {
            t.join(ms)
            if (t.isAlive) { t.interrupt(); t.join(2000) }
        } catch (e: InterruptedException) {
            t.interrupt()
        }
    }

    /** Pulls encoded buffers into the muxer until end-of-stream, an interrupt, or giving up after stop. Uses only its arguments. */
    private fun drain(enc: MediaCodec, mux: MuxSync) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var lastPts = -1L
        try {
            while (!Thread.currentThread().isInterrupted) {
                val i = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequested && System.currentTimeMillis() - stopAtMs > 4000) return
                    }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = mux.addTrack(enc.outputFormat)
                    i >= 0 -> {
                        val buf = enc.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && track >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            var pts = (info.presentationTimeUs - baseUs).coerceAtLeast(0)
                            if (pts <= lastPts) pts = lastPts + 1
                            lastPts = pts
                            info.presentationTimeUs = pts
                            mux.write(track, buf, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(i, false)
                        if (eos) return
                    }
                }
            }
        } catch (e: Exception) {
            // Expected after stop (the codec is stopped underneath us); while recording it means the pipeline broke.
            if (!stopRequested) failure = "Video encoding stopped unexpectedly"
        }
    }

    private fun audioLoop(rec: AudioRecord, enc: MediaCodec, mux: MuxSync) {
        val buf = ByteArray(4096)
        var drainer: Thread? = null
        try {
            rec.startRecording()
            drainer = Thread({ drain(enc, mux) }, "rec-audio-drain").also { it.start() }
            while (!stopRequested && !Thread.currentThread().isInterrupted) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val nowUs = System.nanoTime() / 1000
                val ptsUs = nowUs - (n / 2L) * 1_000_000L / 44100L
                val idx = enc.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val ib: ByteBuffer = enc.getInputBuffer(idx) ?: continue
                    ib.clear(); ib.put(buf, 0, n)
                    enc.queueInputBuffer(idx, 0, n, ptsUs, 0)
                }
            }
            runCatching { rec.stop() }
            val idx = enc.dequeueInputBuffer(50_000)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (e: Exception) {
            if (!stopRequested) failure = "Audio recording stopped unexpectedly"
        } finally {
            joinOrInterrupt(drainer, 5000)
        }
    }

    /** Ends the recording and finalizes the file. Call after the renderer stopped feeding frames. Blocks; safe against a second call. */
    fun stop(): Result<Uri> {
        if (!isRecording) return Result.failure(IllegalStateException("Not recording"))
        if (!stopping.compareAndSet(false, true)) return Result.failure(IllegalStateException("Already stopping"))
        try {
            stopAtMs = System.currentTimeMillis()
            stopRequested = true
            isRecording = false
            runCatching { videoEnc?.signalEndOfInputStream() }
            val vt = videoThread
            val at = audioThread
            val mux = sync
            joinOrInterrupt(vt, 6000)
            joinOrInterrupt(at, 8000)
            // Both threads are finished (or were interrupted and joined): nothing can touch the muxer or codecs any more.
            val stuck = vt?.isAlive == true || at?.isAlive == true
            val started = mux?.finish() == true
            release()
            val u = uri
            fun discard() { u?.let { runCatching { context.contentResolver.delete(it, null, null) } } }
            if (stuck) {
                discard()
                return Result.failure(IllegalStateException("The recording did not stop cleanly, so it was discarded"))
            }
            if (!started || u == null) {
                discard()
                return Result.failure(IllegalStateException(failure ?: "No video was recorded"))
            }
            val published = runCatching {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                check(context.contentResolver.update(u, values, null, null) > 0) { "no row updated" }
            }
            return if (published.isSuccess) Result.success(u) else {
                discard()
                Result.failure(IllegalStateException("Could not save the video to storage"))
            }
        } finally {
            stopping.set(false)
        }
    }

    private fun release() {
        runCatching { videoEnc?.stop() }; runCatching { videoEnc?.release() }
        runCatching { audioEnc?.stop() }; runCatching { audioEnc?.release() }
        runCatching { audioRec?.release() }
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { muxer?.release() }
        runCatching { pfd?.close() }
        videoEnc = null; audioEnc = null; audioRec = null; muxer = null; pfd = null; sync = null
        videoThread = null; audioThread = null
    }

    private fun cleanupFailed() {
        isRecording = false
        release()
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        uri = null
    }

    /** Starts the muxer once every expected track is added; samples that arrive earlier are held back. */
    private class MuxSync(
        private val muxer: MediaMuxer, private val expected: Int, private val onProblem: (String) -> Unit,
    ) {
        private class Sample(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)
        private var added = 0
        @Volatile var started = false
            private set
        private var closed = false
        private val queue = ArrayList<Sample>()
        private var queuedBytes = 0L
        private var firstQueuedAt = 0L

        @Synchronized fun addTrack(format: MediaFormat): Int {
            if (closed) return -1
            val t = muxer.addTrack(format)
            added++
            if (added == expected) {
                muxer.start()
                started = true
                queue.forEach { muxer.writeSampleData(it.track, ByteBuffer.wrap(it.data), it.info) }
                queue.clear()
                queuedBytes = 0
            }
            return t
        }

        @Synchronized fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (closed) return
            if (started) {
                muxer.writeSampleData(track, buf, info)
            } else {
                // Samples wait here until every track is ready; bound both the size and the waiting time.
                val now = SystemClock.elapsedRealtime()
                if (queue.isEmpty()) firstQueuedAt = now
                if (queuedBytes + info.size > MAX_QUEUED_BYTES || now - firstQueuedAt > MAX_QUEUE_WAIT_MS) {
                    closed = true
                    queue.clear()
                    queuedBytes = 0
                    onProblem("The audio or video track did not start in time")
                    return
                }
                queuedBytes += info.size
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                val copy = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                queue += Sample(track, bytes, copy)
            }
        }

        /** Stops the muxer (if it ever started) and refuses further writes. Returns true when a file was finalized. */
        @Synchronized fun finish(): Boolean {
            closed = true
            queue.clear()
            queuedBytes = 0
            return started && runCatching { muxer.stop() }.isSuccess
        }

        private companion object {
            const val MAX_QUEUED_BYTES = 32L * 1024 * 1024
            const val MAX_QUEUE_WAIT_MS = 5000L
        }
    }
}
