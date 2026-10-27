package com.k1epic.phonecast

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
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private val mainHandler by lazy { Handler(mainLooper) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        layout.addView(TextView(this).apply { text = "Phone Cast — آزمایشی"; textSize = 24f })
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
        layout.addView(status)
        layout.addView(address)
        layout.addView(Button(this).apply {
            text = "شروع انتقال تصویر"
            setOnClickListener {
                if (!running.get()) {
                    try {
                        startActivityForResult(projectionManager.createScreenCaptureIntent(), requestCapture)
                    } catch (e: Exception) {
                        showFailure("باز کردن مجوز ضبط صفحه ناموفق بود: ${e.message}")
                    }
                }
            }
        })
        layout.addView(Button(this).apply { text = "توقف انتقال"; setOnClickListener { stopCasting() } })
        setContentView(layout)
    }

    @Deprecated("Deprecated by Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        Log.i(TAG, "Capture result: resultCode=$resultCode, dataNull=${data == null}")
        if (resultCode != Activity.RESULT_OK || data == null) {
            showFailure("مجوز ضبط صفحه تأیید نشد. دوباره شروع را بزنید و «شروع اکنون» را انتخاب کنید.")
            return
        }
        try {
            startCasting(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "startCasting failed", e)
            showFailure("شروع انتقال ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startCasting(resultCode: Int, data: Intent) {
        releaseResources(stopProjection = true)
        status.text = "در حال راه‌اندازی انتقال تصویر…"
        address.text = "در حال آماده‌سازی سرور شبکه…"

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            throw IllegalStateException("ابعاد صفحه قابل تشخیص نیست")
        }
        val width = (metrics.widthPixels.coerceAtMost(960) / 2 * 2).coerceAtLeast(2)
        val height = (metrics.heightPixels * width / metrics.widthPixels / 2 * 2).coerceAtLeast(2)
        Log.i(TAG, "Creating capture display: ${width}x${height}")

        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        reader!!.setOnImageAvailableListener({ imageReader ->
            val image = try { imageReader.acquireLatestImage() } catch (e: Exception) {
                Log.w(TAG, "acquireLatestImage failed", e); null
            } ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val nv21 = ByteArray(width * height * 3 / 2)
                var out = 0
                val yBuffer = planes[0].buffer.duplicate()
                val yBase = yBuffer.position()
                for (row in 0 until height) {
                    for (col in 0 until width) {
                        nv21[out++] = yBuffer.get(yBase + row * planes[0].rowStride + col * planes[0].pixelStride)
                    }
                }
                val uBuffer = planes[1].buffer.duplicate()
                val vBuffer = planes[2].buffer.duplicate()
                val uBase = uBuffer.position()
                val vBase = vBuffer.position()
                for (row in 0 until height / 2) {
                    for (col in 0 until width / 2) {
                        nv21[out++] = vBuffer.get(vBase + row * planes[2].rowStride + col * planes[2].pixelStride)
                        nv21[out++] = uBuffer.get(uBase + row * planes[1].rowStride + col * planes[1].pixelStride)
                    }
                }
                val jpeg = ByteArrayOutputStream()
                if (YuvImage(nv21, ImageFormat.NV21, width, height, null)
                        .compressToJpeg(Rect(0, 0, width, height), 55, jpeg)) {
                    server?.setFrame(jpeg.toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame conversion failed", e)
            } finally {
                image.close()
            }
        }, mainHandler)

        projection = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("سیستم مجوز MediaProjection را برنگرداند")
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                mainHandler.post {
                    if (running.get()) {
                        releaseResources(stopProjection = false)
                        status.text = "سیستم انتقال صفحه را متوقف کرد. دوباره شروع را بزنید."
                        address.text = "اگر تکرار شد، مجوز ضبط صفحه و محدودیت‌های باتری MIUI را بررسی کنید."
                    }
                }
            }
        }, mainHandler)

        val newServer = MjpegServer(
            onReady = {
                mainHandler.post {
                    if (projection != null && display != null) {
                        running.set(true)
                        status.text = "انتقال تصویر فعال است. گوشی و تلویزیون باید روی یک شبکهٔ Wi-Fi باشند."
                        address.text = "در مرورگر Android Box باز کنید:\nhttp://${localIp()}:8080"
                    }
                }
            },
            onError = { e ->
                Log.e(TAG, "HTTP server failed", e)
                mainHandler.post { showFailure("سرور شبکه اجرا نشد: ${e.message ?: e.javaClass.simpleName}") }
            }
        )
        server = newServer
        newServer.start()

        display = projection!!.createVirtualDisplay(
            "PhoneCastDisplay", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, mainHandler
        ) ?: throw IllegalStateException("ساخت VirtualDisplay ناموفق بود")
        Log.i(TAG, "VirtualDisplay created")
    }

    private fun showFailure(message: String) {
        Log.e(TAG, message)
        releaseResources(stopProjection = true)
        status.text = message
        address.text = "برای تلاش دوباره، دکمهٔ شروع انتقال تصویر را بزنید."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopCasting() {
        releaseResources(stopProjection = true)
        if (::status.isInitialized) status.text = "انتقال متوقف است."
        if (::address.isInitialized) address.text = "برای شروع دوباره، دکمهٔ شروع انتقال تصویر را بزنید."
    }

    private fun releaseResources(stopProjection: Boolean) {
        running.set(false)
        try { display?.release() } catch (e: Exception) { Log.w(TAG, "display release", e) }
        display = null
        try { reader?.setOnImageAvailableListener(null, null); reader?.close() } catch (e: Exception) { Log.w(TAG, "reader close", e) }
        reader = null
        val oldProjection = projection
        projection = null
        if (stopProjection) try { oldProjection?.stop() } catch (e: Exception) { Log.w(TAG, "projection stop", e) }
        try { server?.close() } catch (e: Exception) { Log.w(TAG, "server close", e) }
        server = null
    }

    private fun localIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val addresses = interfaces.filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { Collections.list(it.inetAddresses) }
            for (addr in addresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address && !addr.isLinkLocalAddress) {
                    return addr.hostAddress ?: continue
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Could not find local IP", e) }
        return "آدرس شبکه پیدا نشد"
    }

    override fun onDestroy() {
        releaseResources(stopProjection = true)
        super.onDestroy()
    }

    companion object { private const val TAG = "PhoneCast" }
}

private class MjpegServer(
    private val onReady: () -> Unit,
    private val onError: (Exception) -> Unit
) {
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
                Log.i("PhoneCast", "HTTP server listening on 8080")
                onReady()
                while (alive.get()) {
                    val client = ss.accept()
                    Thread({ handle(client) }, "PhoneCastClient").start()
                }
            } catch (e: Exception) {
                if (alive.get()) onError(e)
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
                var lastFrame: ByteArray? = null
                while (alive.get() && !client.isClosed) {
                    val frame = latestFrame
                    if (frame == null || frame === lastFrame) {
                        Thread.sleep(50)
                        continue
                    }
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    lastFrame = frame
                    Thread.sleep(40)
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
