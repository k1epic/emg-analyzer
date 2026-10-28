package com.k1epic.phonecast.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Native Android TV receiver. Uses the sender's framed H.264/AVC endpoint by default,
 * with a legacy MJPEG fallback when H.264 is disabled on the phone.
 */
class TvReceiverActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val connected = AtomicBoolean(false)
    private val decoderLock = Any()

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var outputSurface: Surface? = null
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var decoderOutputThread: Thread? = null
    @Volatile private var decoderWidth = 0
    @Volatile private var decoderHeight = 0
    @Volatile private var firstFrameRendered = false

    private lateinit var videoView: AspectRatioSurfaceView
    private lateinit var image: ImageView
    private lateinit var addressInput: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var panel: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF080A0E.toInt()) }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF080A0E.toInt())
            visibility = View.GONE
            contentDescription = "PhoneCast MJPEG fallback"
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))

        videoView = AspectRatioSurfaceView(this).apply {
            setBackgroundColor(0xFF080A0E.toInt())
            contentDescription = "PhoneCast H.264 video"
        }
        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                outputSurface = holder.surface
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                outputSurface = holder.surface
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                outputSurface = null
                stopDecoder()
            }
        })
        root.addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            setBackgroundColor(0xE8202329.toInt())
        }
        val title = TextView(this).apply {
            text = "PhoneCast TV"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        addressInput = EditText(this).apply {
            hint = "Phone IP:port (e.g. 192.168.1.20:8080)"
            setSingleLine(true)
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFBBBBBB.toInt())
            setPadding(14, 8, 14, 8)
            setText(getPreferences(MODE_PRIVATE).getString("host", "192.168.1.100:8080"))
            setSelectAllOnFocus(true)
        }
        status = TextView(this).apply {
            text = "گوشی و تلویزیون باید به یک شبکهٔ Wi-Fi متصل باشند."
            textSize = 16f
            setTextColor(0xFFE2E5EA.toInt())
            setPadding(0, 8, 0, 8)
        }
        connectButton = Button(this).apply {
            text = "اتصال به PhoneCast"
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                if (connected.get()) disconnect() else connect()
            }
        }
        panel.addView(title)
        panel.addView(addressInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        panel.addView(status)
        panel.addView(connectButton)
        root.addView(panel, FrameLayout.LayoutParams(560, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = 28
            topMargin = 28
        })
        setContentView(root)
        connectButton.requestFocus()
    }

    private fun connect() {
        val host = addressInput.text.toString().trim().removePrefix("http://").removeSuffix("/")
        if (host.isBlank() || host.contains(" ")) {
            status.text = "آدرس IP و پورت را وارد کنید."
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        connected.set(true)
        firstFrameRendered = false
        addressInput.isEnabled = false
        connectButton.text = "قطع اتصال"
        status.text = "در حال اتصال H.264…"
        videoView.visibility = View.VISIBLE
        image.visibility = View.GONE

        worker.execute {
            try {
                val h264 = openConnection(host, "/h264")
                connection = h264
                h264.connect()
                if (h264.responseCode == 415) {
                    h264.disconnect()
                    connection = null
                    connectMjpeg(host)
                    return@execute
                }
                if (h264.responseCode !in 200..299) {
                    throw IllegalStateException(when (h264.responseCode) {
                        409 -> "گیرندهٔ دیگری به PhoneCast متصل است."
                        503 -> "ابتدا انتقال تصویر را در گوشی شروع کنید."
                        else -> "پاسخ سرور H.264: HTTP ${h264.responseCode}"
                    })
                }

                main.post {
                    status.text = "اتصال H.264 برقرار شد؛ در حال دریافت تصویر…"
                    panel.visibility = View.VISIBLE
                }
                DataInputStream(BufferedInputStream(h264.inputStream, 64 * 1024)).use { input ->
                    while (connected.get()) {
                        val payloadSize = try { input.readInt() } catch (_: EOFException) { break }
                        val flags = input.readInt()
                        val width = input.readInt()
                        val height = input.readInt()
                        val pts = input.readLong()
                        if (payloadSize !in 1..16_777_216 || width !in 2..8192 || height !in 2..8192) {
                            throw IllegalStateException("بستهٔ H.264 نامعتبر دریافت شد.")
                        }
                        val payload = ByteArray(payloadSize)
                        input.readFully(payload)
                        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            configureDecoder(width, height, force = true)
                            queueDecoderInput(payload, pts, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        } else {
                            if (decoder == null || width != decoderWidth || height != decoderHeight) {
                                throw IllegalStateException("فریم ویدئو پیش از پیکربندی H.264 دریافت شد.")
                            }
                            queueDecoderInput(payload, pts, 0)
                        }
                    }
                }
                if (connected.get()) throw IllegalStateException("جریان H.264 قطع شد.")
            } catch (e: Exception) {
                if (connected.get()) main.post {
                    status.text = "اتصال ناموفق: ${e.message ?: "خطای شبکه"}"
                    panel.visibility = View.VISIBLE
                    disconnectUiOnly()
                }
            } finally {
                connection?.disconnect()
                connection = null
                stopDecoder()
            }
        }
    }

    private fun openConnection(host: String, path: String): HttpURLConnection {
        return (URL("http://$host$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 15000
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", if (path == "/h264") "application/x-phonecast-avc-framed" else "multipart/x-mixed-replace")
        }
    }

    private fun connectMjpeg(host: String) {
        val conn = openConnection(host, "/stream")
        connection = conn
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(when (conn.responseCode) {
                409 -> "گیرندهٔ دیگری به PhoneCast متصل است."
                503 -> "ابتدا انتقال تصویر را در گوشی شروع کنید."
                else -> "پاسخ سرور: HTTP ${conn.responseCode}"
            })
        }
        main.post {
            videoView.visibility = View.GONE
            image.visibility = View.VISIBLE
            status.text = "حالت سازگاری MJPEG فعال است."
        }
        BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
            readJpegFrames(input) { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) main.post {
                    if (connected.get()) {
                        val previous = image.drawable
                        image.setImageBitmap(bitmap)
                        if (panel.visibility == View.VISIBLE) panel.visibility = View.GONE
                        if (previous is android.graphics.drawable.BitmapDrawable && previous.bitmap !== bitmap) {
                            previous.bitmap.recycle()
                        }
                    } else {
                        bitmap.recycle()
                    }
                }
            }
        }
        if (connected.get()) throw IllegalStateException("جریان MJPEG قطع شد.")
    }

    private fun configureDecoder(width: Int, height: Int, force: Boolean) {
        synchronized(decoderLock) {
            if (!force && decoder != null && decoderWidth == width && decoderHeight == height) return
            releaseDecoderLocked()

            var surface = outputSurface
            var attempts = 0
            while (surface == null && connected.get() && attempts < 100) {
                Thread.sleep(10)
                surface = outputSurface
                attempts++
            }
            if (surface == null) throw IllegalStateException("سطح نمایش ویدئو آماده نیست.")

            var codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            if (Build.VERSION.SDK_INT >= 30) {
                try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) } catch (_: Exception) {}
            }
            try {
                codec.configure(format, surface, null, 0)
            } catch (first: Exception) {
                try { codec.release() } catch (_: Exception) {}
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val compatibleFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                try {
                    codec.configure(compatibleFormat, surface, null, 0)
                } catch (second: Exception) {
                    try { codec.release() } catch (_: Exception) {}
                    throw IllegalStateException("راه‌اندازی رمزگشای H.264 ناموفق بود: ${second.message ?: first.message}", second)
                }
            }
            try {
                codec.start()
            } catch (e: Exception) {
                try { codec.release() } catch (_: Exception) {}
                throw IllegalStateException("شروع رمزگشای H.264 ناموفق بود: ${e.message}", e)
            }
            decoder = codec
            decoderWidth = width
            decoderHeight = height
            videoView.setVideoSize(width, height)
            decoderOutputThread = Thread({ drainDecoderOutput(codec) }, "PhoneCastAvcRender").apply {
                isDaemon = true
                start()
            }
            main.post {
                videoView.visibility = View.VISIBLE
                image.visibility = View.GONE
            }
        }
    }

    private fun queueDecoderInput(payload: ByteArray, pts: Long, flags: Int) {
        synchronized(decoderLock) {
            val active = decoder ?: throw IllegalStateException("رمزگشای H.264 آماده نیست.")
            var inputIndex = active.dequeueInputBuffer(10_000)
            // TV Box decoders can temporarily have no free input buffer; wait briefly instead of dropping the connection.
            var waitAttempts = 0
            while (inputIndex < 0 && connected.get() && waitAttempts < 25) {
                inputIndex = active.dequeueInputBuffer(20_000)
                waitAttempts++
            }
            if (inputIndex < 0) throw IllegalStateException("رمزگشای H.264 برای دریافت فریم آماده نشد؛ کیفیت یا نرخ فریم را کاهش دهید.")
            val input = active.getInputBuffer(inputIndex)
                ?: throw IllegalStateException("بافر ورودی H.264 در دسترس نیست.")
            input.clear()
            if (payload.size > input.remaining()) {
                active.queueInputBuffer(inputIndex, 0, 0, pts, 0)
                throw IllegalStateException("اندازهٔ فریم H.264 از ظرفیت بافر بیشتر است.")
            }
            input.put(payload)
            active.queueInputBuffer(inputIndex, 0, payload.size, pts, flags)
        }
    }

    private fun drainDecoderOutput(active: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (connected.get() && decoder === active) {
                when (val index = active.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        active.releaseOutputBuffer(index, true)
                        if (!firstFrameRendered) {
                            firstFrameRendered = true
                            main.post {
                                if (connected.get()) {
                                    panel.visibility = View.GONE
                                    status.text = "پخش H.264 فعال است."
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (connected.get() && decoder === active) {
                main.post { status.text = "خطای رمزگشایی H.264: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun releaseDecoderLocked() {
        val old = decoder
        decoder = null
        decoderWidth = 0
        decoderHeight = 0
        try { decoderOutputThread?.interrupt() } catch (_: Exception) {}
        decoderOutputThread = null
        if (old != null) {
            try { old.stop() } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
        }
    }

    private fun stopDecoder() {
        synchronized(decoderLock) { releaseDecoderLocked() }
    }

    /** MJPEG compatibility parser used only if H.264 is disabled in the sender settings. */
    private fun readJpegFrames(input: BufferedInputStream, onFrame: (ByteArray) -> Unit) {
        var previous = -1
        var capturing = false
        val frame = ByteArrayOutputStream(256 * 1024)
        while (connected.get()) {
            val value = input.read()
            if (value < 0) break
            if (!capturing) {
                if (previous == 0xFF && value == 0xD8) {
                    frame.reset()
                    frame.write(0xFF)
                    frame.write(0xD8)
                    capturing = true
                }
            } else {
                frame.write(value)
                if (previous == 0xFF && value == 0xD9) {
                    val jpeg = frame.toByteArray()
                    if (jpeg.size > 4) onFrame(jpeg)
                    capturing = false
                }
                if (frame.size() > 16 * 1024 * 1024) {
                    frame.reset()
                    capturing = false
                }
            }
            previous = value
        }
    }

    private fun disconnect() {
        connected.set(false)
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        stopDecoder()
        disconnectUiOnly()
        panel.visibility = View.VISIBLE
        status.text = "اتصال قطع شد."
    }

    private fun disconnectUiOnly() {
        connected.set(false)
        addressInput.isEnabled = true
        connectButton.text = "اتصال به PhoneCast"
    }

    override fun onBackPressed() {
        if (panel.visibility == View.GONE) {
            panel.visibility = View.VISIBLE
        } else {
            disconnect()
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        connected.set(false)
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        stopDecoder()
        worker.shutdownNow()
        super.onDestroy()
    }

    private class AspectRatioSurfaceView(context: android.content.Context) : SurfaceView(context) {
        @Volatile private var videoWidth = 0
        @Volatile private var videoHeight = 0

        fun setVideoSize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            videoWidth = width
            videoHeight = height
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val maxHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
            val vw = videoWidth
            val vh = videoHeight
            if (vw <= 0 || vh <= 0) {
                setMeasuredDimension(maxWidth, maxHeight)
                return
            }
            val aspect = vw.toFloat() / vh.toFloat()
            var width = maxWidth
            var height = (width / aspect).roundToInt()
            if (height > maxHeight) {
                height = maxHeight
                width = (height * aspect).roundToInt()
            }
            setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
    }
}
