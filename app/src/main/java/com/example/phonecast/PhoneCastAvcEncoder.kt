package com.k1epic.phonecast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware AVC/H.264 encoder. VirtualDisplay writes directly to the encoder Surface,
 * avoiding the RGBA -> Bitmap -> JPEG CPU conversion in MJPEG mode.
 *
 * Network packet protocol (big endian): payloadLength:int, flags:int, width:int,
 * height:int, presentationTimeUs:long, payload bytes.
 */
internal class PhoneCastAvcEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    private val onPacket: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var drainThread: Thread? = null
    @Volatile private var sentFormatConfig = false

    fun start(): Surface {
        if (width < 2 || height < 2 || width % 2 != 0 || height % 2 != 0) {
            throw IllegalArgumentException("H.264 dimensions must be positive even numbers")
        }

        var candidate = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            candidate.configure(buildFormat(includeLatencyHints = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (first: Exception) {
            try { candidate.release() } catch (_: Exception) {}
            candidate = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                candidate.configure(buildFormat(includeLatencyHints = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (second: Exception) {
                try { candidate.release() } catch (_: Exception) {}
                throw IllegalStateException("این دستگاه نتوانست انکودر H.264 را راه‌اندازی کند: ${second.message ?: first.message}", second)
            }
        }

        val surface = try {
            candidate.createInputSurface()
        } catch (e: Exception) {
            try { candidate.release() } catch (_: Exception) {}
            throw IllegalStateException("ساخت ورودی Surface برای H.264 ناموفق بود: ${e.message}", e)
        }

        try {
            candidate.start()
        } catch (e: Exception) {
            try { surface.release() } catch (_: Exception) {}
            try { candidate.release() } catch (_: Exception) {}
            throw IllegalStateException("شروع انکودر H.264 ناموفق بود: ${e.message}", e)
        }

        codec = candidate
        inputSurface = surface
        sentFormatConfig = false
        running.set(true)
        drainThread = Thread({ drainOutput(candidate) }, "PhoneCastAvcDrain").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "H.264 encoder started: ${width}x${height} @ ${frameRate}fps, ${bitRate}bps")
        return surface
    }

    private fun buildFormat(includeLatencyHints: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate.coerceIn(500_000, 20_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceIn(5, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (includeLatencyHints) {
                // Baseline AVC has the broadest hardware-decoder compatibility on Android TV boxes.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                if (Build.VERSION.SDK_INT >= 26) setInteger(MediaFormat.KEY_LATENCY, 1)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }
    }

    fun requestSyncFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not request H.264 key frame: ${e.message}")
        }
    }

    private fun drainOutput(activeCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                when (val index = activeCodec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = activeCodec.outputFormat
                        val chunks = ArrayList<ByteArray>(2)
                        byteArrayFromFormat(format, "csd-0")?.let { chunks.add(it) }
                        byteArrayFromFormat(format, "csd-1")?.let { chunks.add(it) }
                        if (chunks.isNotEmpty()) {
                            val total = chunks.sumOf { it.size }
                            val csd = ByteArray(total)
                            var offset = 0
                            chunks.forEach {
                                System.arraycopy(it, 0, csd, offset, it.size)
                                offset += it.size
                            }
                            onPacket(makePacket(csd, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, width, height, 0L))
                            sentFormatConfig = true
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                val buffer = activeCodec.getOutputBuffer(index)
                                if (buffer != null) {
                                    val data = buffer.duplicate()
                                    data.position(info.offset)
                                    data.limit(info.offset + info.size)
                                    val payload = ByteArray(info.size)
                                    data.get(payload)
                                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        if (!sentFormatConfig) {
                                            onPacket(makePacket(payload, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, width, height, info.presentationTimeUs))
                                            sentFormatConfig = true
                                        }
                                    } else {
                                        onPacket(makePacket(payload, info.flags, width, height, info.presentationTimeUs))
                                    }
                                }
                            }
                        } finally {
                            activeCodec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "H.264 output drain failed", e)
                onError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun byteArrayFromFormat(format: MediaFormat, key: String): ByteArray? {
        return try {
            val source = format.getByteBuffer(key)?.duplicate() ?: return null
            val bytes = ByteArray(source.remaining())
            source.get(bytes)
            bytes.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun makePacket(
        payload: ByteArray,
        flags: Int,
        frameWidth: Int,
        frameHeight: Int,
        presentationTimeUs: Long
    ): ByteArray {
        val packet = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.BIG_ENDIAN)
        packet.putInt(payload.size)
        packet.putInt(flags)
        packet.putInt(frameWidth)
        packet.putInt(frameHeight)
        packet.putLong(presentationTimeUs)
        packet.put(payload)
        return packet.array()
    }

    fun stop() {
        running.set(false)
        try { drainThread?.interrupt() } catch (_: Exception) {}
        drainThread = null
        val active = codec
        codec = null
        if (active != null) {
            try { active.stop() } catch (_: Exception) {}
            try { active.release() } catch (_: Exception) {}
        }
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    companion object {
        private const val TAG = "PhoneCastAvcEncoder"
    }
}
