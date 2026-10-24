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
import android.provider.MediaStore
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Records the filtered frames that the GL renderer draws into [start]'s returned Surface, plus microphone audio,
 * into Movies/FilterCam via MediaStore. Frame and audio timestamps both come from System.nanoTime().
 * start() and stop() may be called from any thread; stop() blocks until the file is finalized, so call it off the main thread.
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
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var stopAtMs = 0L
    private var baseUs = 0L
    @Volatile var isRecording = false
        private set
    @Volatile var hasAudio = false
        private set

    /** Starts the encoders and returns the surface the renderer must draw into. Throws if anything fails (nothing is left behind). */
    @SuppressLint("MissingPermission")
    fun start(width: Int, height: Int, withAudio: Boolean): Surface {
        check(!isRecording) { "Already recording" }
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
            muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 8L).coerceIn(4_000_000L, 20_000_000L).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEnc = venc
            venc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inSurface = venc.createInputSurface()
            inputSurface = inSurface
            venc.start()

            var audioOk = false
            if (withAudio) {
                audioOk = runCatching { setupAudio() }.getOrDefault(false)
            }
            hasAudio = audioOk
            sync = MuxSync(muxer!!, if (audioOk) 2 else 1)

            baseUs = System.nanoTime() / 1000
            stopRequested = false
            isRecording = true
            videoThread = Thread({ drain(venc, EncoderKind.VIDEO) }, "rec-video").also { it.start() }
            if (audioOk) audioThread = Thread({ audioLoop() }, "rec-audio").also { it.start() }
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
        val aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aenc.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aenc.start()
        audioRec = rec
        audioEnc = aenc
        return true
    }

    private enum class EncoderKind { VIDEO, AUDIO }

    /** Pulls encoded buffers into the muxer until the encoder reports end-of-stream (or we give up after stop). */
    private fun drain(enc: MediaCodec, kind: EncoderKind, untilEos: Boolean = true) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var lastPts = -1L
        while (true) {
            val i = enc.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (stopRequested && System.currentTimeMillis() - stopAtMs > 4000) return
                    if (!untilEos) return
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = sync!!.addTrack(enc.outputFormat)
                i >= 0 -> {
                    val buf = enc.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && track >= 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        var pts = (info.presentationTimeUs - baseUs).coerceAtLeast(0)
                        if (pts <= lastPts) pts = lastPts + 1
                        lastPts = pts
                        info.presentationTimeUs = pts
                        sync!!.write(track, buf, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(i, false)
                    if (eos) return
                }
            }
        }
    }

    private fun audioLoop() {
        val rec = audioRec!!
        val enc = audioEnc!!
        val buf = ByteArray(4096)
        rec.startRecording()
        val drainer = Thread({ drain(enc, EncoderKind.AUDIO) }, "rec-audio-drain").also { it.start() }
        while (!stopRequested) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val nowUs = System.nanoTime() / 1000
            val ptsUs = nowUs - (n / 2L) * 1_000_000L / 44100L
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib: ByteBuffer = enc.getInputBuffer(idx)!!
                ib.clear(); ib.put(buf, 0, n)
                enc.queueInputBuffer(idx, 0, n, ptsUs, 0)
            }
        }
        rec.stop()
        val idx = enc.dequeueInputBuffer(50_000)
        if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainer.join(5000)
    }

    /** Ends the recording and finalizes the file. Call after the renderer stopped feeding frames. Blocks. */
    fun stop(): Result<Uri> {
        if (!isRecording) return Result.failure(IllegalStateException("Not recording"))
        stopAtMs = System.currentTimeMillis()
        stopRequested = true
        isRecording = false
        runCatching { videoEnc?.signalEndOfInputStream() }
        videoThread?.join(6000)
        audioThread?.join(6000)
        val started = sync?.started == true
        runCatching { if (started) muxer?.stop() }
        release()
        val u = uri
        return if (started && u != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(u, values, null, null)
            Result.success(u)
        } else {
            u?.let { context.contentResolver.delete(it, null, null) }
            Result.failure(IllegalStateException("No video was recorded"))
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
    private class MuxSync(private val muxer: MediaMuxer, private val expected: Int) {
        private class Sample(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)
        private var added = 0
        @Volatile var started = false
            private set
        private val queue = ArrayList<Sample>()

        @Synchronized fun addTrack(format: MediaFormat): Int {
            val t = muxer.addTrack(format)
            added++
            if (added == expected) {
                muxer.start()
                started = true
                queue.forEach { muxer.writeSampleData(it.track, ByteBuffer.wrap(it.data), it.info) }
                queue.clear()
            }
            return t
        }

        @Synchronized fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started) {
                muxer.writeSampleData(track, buf, info)
            } else {
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                val copy = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                queue += Sample(track, bytes, copy)
            }
        }
    }
}
