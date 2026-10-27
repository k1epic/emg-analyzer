package com.example.phonecast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var server: MjpegServer? = null
    private val running = AtomicBoolean(false)
    private val requestCapture = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        val title = TextView(this).apply {
            text = "Phone Cast — آزمایشی"
            textSize = 24f
        }
        status = TextView(this).apply {
            text = "برای شروع، اشتراک‌گذاری صفحه را فعال کنید."
            textSize = 16f
            setPadding(0, 28, 0, 12)
        }
        address = TextView(this).apply {
            text = "گیرندهٔ وب پس از شروع در اینجا نمایش داده می‌شود."
            textSize = 16f
            setPadding(0, 8, 0, 20)
        }
        val start = Button(this).apply {
            text = "شروع انتقال تصویر"
            setOnClickListener {
                if (!running.get()) startActivityForResult(projectionManager.createScreenCaptureIntent(), requestCapture)
            }
        }
        val stop = Button(this).apply {
            text = "توقف انتقال"
            setOnClickListener { stopCasting() }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(address)
        layout.addView(start)
        layout.addView(stop)
        setContentView(layout)
    }

    @Deprecated("Deprecated by Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            status.text = "مجوز ضبط صفحه داده نشد."
            return
        }
        try {
            startCasting(resultCode, data)
        } catch (e: Exception) {
            status.text = "شروع انتقال ناموفق بود: ${e.message ?: "خطای نامشخص"}"
            stopCasting()
        }
    }

    private fun startCasting(resultCode: Int, data: Intent) {
        stopCasting()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = (metrics.widthPixels.coerceAtMost(1280) / 2 * 2).coerceAtLeast(2)
        val height = (metrics.heightPixels * width / metrics.widthPixels).coerceAtMost(1280).coerceAtLeast(2)

        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        reader!!.setOnImageAvailableListener({ imageReader ->
            val image = try { imageReader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val y = planes[0].buffer
                val u = planes[1].buffer
                val v = planes[2].buffer
                val ySize = y.remaining()
                val uSize = u.remaining()
                val vSize = v.remaining()
                val nv21 = ByteArray(ySize + uSize + vSize)
                y.get(nv21, 0, ySize)
                val chromaWidth = width / 2
                val chromaHeight = height / 2
                var out = ySize
                for (row in 0 until chromaHeight) {
                    for (col in 0 until chromaWidth) {
                        val ui = row * planes[1].rowStride + col * planes[1].pixelStride
                        val vi = row * planes[2].rowStride + col * planes[2].pixelStride
                        if (vi < vSize && ui < uSize && out + 1 < nv21.size) {
                            nv21[out++] = v.get(vi)
                            nv21[out++] = u.get(ui)
                        }
                    }
                }
                val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                val jpeg = ByteArrayOutputStream()
                yuv.compressToJpeg(Rect(0, 0, width, height), 55, jpeg)
                server?.setFrame(jpeg.toByteArray())
            } catch (_: Exception) {
                // Ignore a single malformed frame; the next frame can recover.
            } finally {
                image.close()
            }
        }, android.os.Handler(mainLooper))

        projection = projectionManager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { runOnUiThread { stopCasting() } }
        }, android.os.Handler(mainLooper))

        server = MjpegServer()
        server!!.start()
        display = projection?.createVirtualDisplay(
            "PhoneCastDisplay", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )
        running.set(true)
        status.text = "انتقال تصویر فعال است. گوشی و تلویزیون باید روی یک Wi-Fi باشند."
        address.text = "در مرورگر Android Box باز کنید:\nhttp://${localIp()}:8080"
    }

    private fun stopCasting() {
        running.set(false)
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        if (::status.isInitialized) status.text = "انتقال متوقف است."
        if (::address.isInitialized) address.text = "برای شروع دوباره، دکمهٔ شروع انتقال تصویر را بزنید."
    }

    private fun localIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: "127.0.0.1"
                }
            }
        } catch (_: Exception) {}
        return "آدرس شبکه پیدا نشد"
    }

    override fun onDestroy() {
        stopCasting()
        super.onDestroy()
    }
}

private class MjpegServer {
    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var socketServer: ServerSocket? = null
    private val alive = AtomicBoolean(false)

    fun setFrame(bytes: ByteArray) { latestFrame = bytes }

    fun start() {
        if (!alive.compareAndSet(false, true)) return
        Thread({
            try {
                val ss = ServerSocket(8080)
                socketServer = ss
                while (alive.get()) {
                    val client = ss.accept()
                    Thread({ handle(client) }, "PhoneCastClient").start()
                }
            } catch (_: Exception) {
                alive.set(false)
            }
        }, "PhoneCastHTTP").start()
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = client.getInputStream()
            val request = ByteArray(2048)
            val count = input.read(request)
            val line = if (count > 0) String(request, 0, count, Charsets.US_ASCII).lineSequence().firstOrNull().orEmpty() else ""
            val out = client.getOutputStream()
            if (line.contains("/stream")) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n").toByteArray())
                out.flush()
                while (alive.get() && !client.isClosed) {
                    val frame = latestFrame
                    if (frame == null) {
                        Thread.sleep(100)
                        continue
                    }
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    Thread.sleep(70)
                }
            } else {
                val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Phone Cast</title><style>html,body{margin:0;background:#111;color:#fff;font:16px sans-serif;height:100%;display:grid;place-items:center}main{width:100%;text-align:center}img{width:100%;height:auto;max-height:90vh;object-fit:contain}p{padding:8px}</style></head><body><main><p>Phone Cast — گیرندهٔ وب</p><img src="/stream" alt="Live phone screen"></main></body></html>"""
                val bytes = html.toByteArray(Charsets.UTF_8)
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        alive.set(false)
        try { socketServer?.close() } catch (_: Exception) {}
        socketServer = null
    }
}
