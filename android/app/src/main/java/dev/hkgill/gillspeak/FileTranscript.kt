package dev.hkgill.gillspeak

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteOrder

/**
 * Transcribes an audio file shared to gillspeak (a call recording, a voice memo) with the selected speech model, on
 * the phone. The file is decoded and cut into pieces as it goes, so a long recording never sits in memory whole.
 * Only the text is kept, in memory; the audio is never copied.
 */
internal class FileTranscript private constructor(val name: String) {
    sealed interface State {
        data object Running : State
        data object Done : State
        data object Cancelled : State
        data class Failed(val message: String) : State
    }

    @Volatile var state: State = State.Running
        private set
    @Volatile var durationMs = 0L
        private set
    @Volatile var doneMs = 0L
        private set
    private val found = mutableListOf<TranscriptPiece>()
    val pieces: List<TranscriptPiece> get() = synchronized(found) { found.toList() }
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    private class Stop : RuntimeException()

    private fun run(context: Context, uri: Uri) {
        val t0 = System.nanoTime()
        try {
            val settings = Settings(context)
            val model = settings.speechModel
            if (!LocalAsr.isReady(context, model)) throw IllegalStateException("Download the ${model.title} speech model in gillspeak first")
            // Spoken commands stay off: "new line" or "full stop" in a conversation are just words.
            val rules = Rules(settings.dictionary(), spokenCommands = false)
            val segmenter = Segmenter { start, pcm ->
                if (cancelled) throw Stop()
                val startMs = start * 1000 / Recorder.RATE
                if (!TranscriptText.silent(pcm)) {
                    val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(pcm)
                    val text = rules.apply(LocalAsr.transcribe(context, Recorder.wav(bytes.array()), model))
                    if (text.isNotBlank()) synchronized(found) { found += TranscriptPiece(startMs, text) }
                }
                doneMs = startMs + pcm.size * 1000L / Recorder.RATE
            }
            // An open descriptor keeps working even if the sharing app's permission to the file lapses mid-way.
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Can't open the file")
            pfd.use { decode(it.fileDescriptor) { pcm, n -> segmenter.add(pcm, n) } }
            if (cancelled) throw Stop()
            segmenter.finish()
            doneMs = durationMs.coerceAtLeast(doneMs)
            state = State.Done
            Log.i(Dictation.TAG, "file: ${pieces.size} pieces from ${durationMs / 1000} s of audio in ${(System.nanoTime() - t0) / 1_000_000} ms (${model.title})")
        } catch (_: Stop) {
            state = State.Cancelled
        } catch (e: Exception) {
            Log.e(Dictation.TAG, "file transcription failed", e)
            state = State.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't read that recording (${e.javaClass.simpleName})")
        }
    }

    /** Decodes the file's first audio track to 16 kHz mono PCM16, calling [out] as samples come. */
    private fun decode(fd: FileDescriptor, out: (ShortArray, Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(fd)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("That file has no audio in it")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw IllegalStateException("This phone can't decode $mime audio")
            }
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var resampler = Resampler(rate, channels)
            var shorts = ShortArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (!cancelled) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler.finish(out)
                    resampler = Resampler(rate, channels)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset).limit(info.offset + info.size)
                    val count = if (float) info.size / 4 else info.size / 2
                    if (shorts.size < count) shorts = ShortArray(count)
                    if (float) {
                        val fb = buf.asFloatBuffer()
                        for (k in 0 until count) shorts[k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    } else {
                        buf.asShortBuffer().get(shorts, 0, count)
                    }
                    codec.releaseOutputBuffer(o, false)
                    val whole = count - count % channels
                    if (whole > 0) resampler.feed(shorts, whole, out)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        resampler.finish(out)
                        break
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    companion object {
        /** The one transcription in progress or last finished; a new share replaces a finished one. */
        @Volatile var current: FileTranscript? = null
            private set

        fun start(context: Context, uri: Uri, name: String): FileTranscript {
            val job = FileTranscript(name)
            current = job
            val app = context.applicationContext
            Thread({ job.run(app, uri) }, "gillspeak-file").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
            return job
        }
    }
}
