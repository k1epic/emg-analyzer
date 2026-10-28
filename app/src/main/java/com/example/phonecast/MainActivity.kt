package com.k1epic.phonecast

import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.widget.EditText
import android.widget.ScrollView
import android.text.InputType
import android.app.AlertDialog
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
    private val prefs by lazy { getSharedPreferences("phonecast_settings", Context.MODE_PRIVATE) }
    @Volatile private var lastEncodedAt = 0L
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var server: MjpegServer? = null
    private val running = AtomicBoolean(false)
    private val requestCapture = 1001
    private var pendingCaptureResultCode: Int? = null
    private var pendingCaptureData: Intent? = null
    private var receiverRegistered = false
    private val projectionServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ProjectionForegroundService.ACTION_READY) return
            val resultCode = pendingCaptureResultCode
            val data = pendingCaptureData
            pendingCaptureResultCode = null
            pendingCaptureData = null
            if (resultCode != null && data != null && !running.get()) {
                try {
                    startCasting(resultCode, data)
                } catch (e: Exception) {
                    Log.e(TAG, "startCasting failed after foreground service startup", e)
                    showFailure("شروع انتقال ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }
    private val mainHandler by lazy { Handler(mainLooper) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        registerReceiver(projectionServiceReceiver, IntentFilter(ProjectionForegroundService.ACTION_READY))
        receiverRegistered = true
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
        layout.addView(Button(this).apply { text = "تنظیمات"; setOnClickListener { showSettings() } })
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
        // Android requires a foreground service of type mediaProjection before
        // MediaProjection.getMediaProjection() is called.
        pendingCaptureResultCode = resultCode
        pendingCaptureData = data
        try {
            val serviceIntent = Intent(this, ProjectionForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            status.text = "در حال آماده‌سازی سرویس ضبط صفحه…"
            address.text = "لطفاً چند لحظه صبر کنید."
        } catch (e: Exception) {
            pendingCaptureResultCode = null
            pendingCaptureData = null
            Log.e(TAG, "Could not start projection foreground service", e)
            showFailure("راه‌اندازی سرویس ضبط صفحه ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
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
        val maxWidth = prefs.getInt("max_width", 960).coerceIn(480, 960)
        val width = (metrics.widthPixels.coerceAtMost(maxWidth) / 2 * 2).coerceAtLeast(2)
        val height = (metrics.heightPixels * width / metrics.widthPixels / 2 * 2).coerceAtLeast(2)
        Log.i(TAG, "Creating capture display: ${width}x${height}")

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ imageReader ->
            server?.noteImageCallback()
            val image = try { imageReader.acquireLatestImage() } catch (e: Exception) {
                Log.w(TAG, "acquireLatestImage failed", e)
                server?.noteAcquireError(e.javaClass.simpleName + ": " + (e.message ?: "unknown"))
                null
            } ?: run {
                server?.noteNullImage()
                return@setOnImageAvailableListener
            }
            server?.noteImageAcquired()
            try {
                val now = System.currentTimeMillis()
                val targetFps = prefs.getInt("target_fps", 15).coerceIn(5, 30)
                if (now - lastEncodedAt < 1000L / targetFps) return@setOnImageAvailableListener
                lastEncodedAt = now
                // VirtualDisplay produces RGBA_8888 on this device.
                val plane = image.planes[0]
                val buffer = plane.buffer.duplicate()
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val base = buffer.position()
                val pixels = IntArray(width * height)
                for (row in 0 until height) {
                    val rowStart = base + row * rowStride
                    for (col in 0 until width) {
                        val offset = rowStart + col * pixelStride
                        val r = buffer.get(offset).toInt() and 0xff
                        val g = buffer.get(offset + 1).toInt() and 0xff
                        val b = buffer.get(offset + 2).toInt() and 0xff
                        val a = buffer.get(offset + 3).toInt() and 0xff
                        pixels[row * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    val jpeg = ByteArrayOutputStream()
                    val jpegQuality = prefs.getInt("jpeg_quality", 65).coerceIn(40, 85)
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpeg)) {
                        server?.setFrame(jpeg.toByteArray())
                    } else {
                        server?.noteJpegFailure()
                        Log.e(TAG, "Bitmap.compress returned false for ${width}x${height}")
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame conversion failed", e)
                server?.setFrameError(e.javaClass.simpleName + ": " + (e.message ?: "unknown"))
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

        val serverPort = prefs.getInt("server_port", 8080).coerceIn(1024, 65535)
        val displayName = prefs.getString("display_name", "PhoneCast")?.take(40) ?: "PhoneCast"
        val newServer = MjpegServer(
            port = serverPort,
            displayName = displayName,
            onReady = {
                mainHandler.post {
                    if (projection != null && display != null) {
                        running.set(true)
                        status.text = "انتقال تصویر فعال است. گوشی و تلویزیون باید روی یک شبکهٔ Wi-Fi باشند."
                        address.text = "در مرورگر Android Box باز کنید:\nhttp://${localIp()}:$serverPort"
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

    private fun showSettings() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 8) }
        fun field(label: String, value: String, numeric: Boolean = false): EditText {
            panel.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, 10, 0, 2) })
            return EditText(this).apply {
                setSingleLine(true); setText(value)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
                panel.addView(this)
            }
        }
        val nameField = field("نام نمایشی روی تصویر وب", prefs.getString("display_name", "PhoneCast") ?: "PhoneCast")
        val portField = field("Port (1024 تا 65535)", prefs.getInt("server_port", 8080).toString(), true)
        val fpsField = field("نرخ فریم هدف (5 تا 30)", prefs.getInt("target_fps", 15).toString(), true)
        val widthField = field("حداکثر عرض تصویر (480 / 640 / 960)", prefs.getInt("max_width", 960).toString(), true)
        val qualityField = field("کیفیت JPEG (40 تا 85)", prefs.getInt("jpeg_quality", 65).toString(), true)
        panel.addView(TextView(this).apply {
            text = "IP گوشی به‌صورت خودکار از شبکهٔ Wi-Fi تشخیص داده می‌شود؛ برای تغییر IP باید تنظیمات شبکهٔ گوشی/روتر را تغییر دهید."
            textSize = 12f; setPadding(0, 12, 0, 0)
        })
        AlertDialog.Builder(this).setTitle("تنظیمات PhoneCast").setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val port = portField.text.toString().toIntOrNull()
                val fps = fpsField.text.toString().toIntOrNull()
                val width = widthField.text.toString().toIntOrNull()
                val quality = qualityField.text.toString().toIntOrNull()
                if (port == null || port !in 1024..65535 || fps == null || fps !in 5..30 ||
                    width == null || width !in listOf(480, 640, 960) || quality == null || quality !in 40..85) {
                    Toast.makeText(this, "مقادیر نامعتبر است؛ پورت 1024–65535، FPS برابر 5–30، عرض 480/640/960 و کیفیت 40–85 باشد.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("display_name", nameField.text.toString().trim().ifBlank { "PhoneCast" }.take(40))
                    .putInt("server_port", port).putInt("target_fps", fps).putInt("max_width", width)
                    .putInt("jpeg_quality", quality).apply()
                Toast.makeText(this, "تنظیمات ذخیره شد. برای اعمال پورت جدید، انتقال را متوقف و دوباره شروع کنید.", Toast.LENGTH_LONG).show()
                if (::address.isInitialized) address.text = "آدرس گیرنده: http://${localIp()}:$port"
            }.show()
    }

    private fun showFailure(message: String) {
        Log.e(TAG, message)
        releaseResources(stopProjection = true)
        status.text = message
        address.text = "برای تلاش دوباره، دکمهٔ شروع انتقال تصویر را بزنید."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopCasting() {
        pendingCaptureResultCode = null
        pendingCaptureData = null
        releaseResources(stopProjection = true)
        try { stopService(Intent(this, ProjectionForegroundService::class.java)) } catch (e: Exception) { Log.w(TAG, "service stop", e) }
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
        try { stopService(Intent(this, ProjectionForegroundService::class.java)) } catch (e: Exception) { Log.w(TAG, "service stop on destroy", e) }
        if (receiverRegistered) {
            try { unregisterReceiver(projectionServiceReceiver) } catch (e: Exception) { Log.w(TAG, "receiver unregister", e) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    companion object { private const val TAG = "PhoneCast" }
}

private class MjpegServer(
    private val port: Int,
    private val displayName: String,
    private val onReady: () -> Unit,
    private val onError: (Exception) -> Unit
) {
    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var socketServer: ServerSocket? = null
    private val alive = AtomicBoolean(false)
    @Volatile private var imageCallbacks: Long = 0
    @Volatile private var acquiredImages: Long = 0
    @Volatile private var jpegFailures: Long = 0
    @Volatile private var nullImages: Long = 0
    @Volatile private var acquireErrors: Long = 0
    @Volatile private var lastAcquireError: String = ""
    @Volatile private var frameCount: Long = 0
    @Volatile private var lastFrameBytes: Int = 0
    @Volatile private var lastFrameAt: Long = 0
    @Volatile private var lastFrameError: String = "No frame received yet"

    fun noteImageCallback() { imageCallbacks++ }
    fun noteImageAcquired() { acquiredImages++ }
    fun noteJpegFailure() { jpegFailures++ }
    fun noteNullImage() { nullImages++ }
    fun noteAcquireError(message: String) {
        acquireErrors++
        lastAcquireError = message.replace("\\", "/").replace("\r", " ").replace("\n", " ")
    }

    fun setFrameError(message: String) { lastFrameError = message.replace("\\", "/").replace("\r", " ").replace("\n", " ") }

    fun setFrame(bytes: ByteArray) {
        latestFrame = bytes
        frameCount++
        lastFrameBytes = bytes.size
        lastFrameAt = System.currentTimeMillis()
        lastFrameError = ""
    }

    fun start() {
        if (!alive.compareAndSet(false, true)) return
        Thread({
            try {
                val ss = ServerSocket(port)
                socketServer = ss
                Log.i("PhoneCast", "HTTP server listening on $port")
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
            if (line.startsWith("GET /status")) {
                val age = if (lastFrameAt == 0L) -1L else System.currentTimeMillis() - lastFrameAt
                val safeError = lastFrameError.replace("\\", "/").replace("\r", " ").replace("\n", " ").replace("\"", "'")
                val body = "callbacks=" + imageCallbacks + "\nimagesAcquired=" + acquiredImages + "\nnullImages=" + nullImages + "\nacquireErrors=" + acquireErrors + "\nlastAcquireError=" + lastAcquireError + "\njpegFailures=" + jpegFailures + "\nframes=" + frameCount + "\njpegBytes=" + lastFrameBytes + "\nlastFrameAgeMs=" + age + "\nerror=" + safeError + "\n"
                val bytes = body.toByteArray(Charsets.UTF_8)
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-cache\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes)
                out.flush()
            } else if (line.contains("/stream")) {
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
                val safeName = displayName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
                val html = """<!doctype html>
<html lang="fa"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover"><title>PhoneCast</title>
<style>
*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#080a0e;color:#fff;font:14px system-ui,sans-serif}
main{position:relative;width:100vw;height:100dvh;display:flex;align-items:center;justify-content:center;background:#080a0e}
#screen{display:block;max-width:100vw;max-height:100dvh;width:auto;height:auto;object-fit:contain}
#name{position:absolute;top:max(10px,env(safe-area-inset-top));left:max(10px,env(safe-area-inset-left));z-index:2;padding:5px 9px;border-radius:5px;background:#0009;color:#fff;font-size:clamp(11px,1.5vw,16px);max-width:65vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#credit{position:absolute;right:max(8px,env(safe-area-inset-right));bottom:max(7px,env(safe-area-inset-bottom));z-index:2;padding:3px 6px;border-radius:4px;background:#0008;color:#fff;font-size:10px;letter-spacing:.15px}
#tools{position:absolute;right:10px;top:10px;z-index:3;opacity:.28;transition:opacity .2s}#tools:hover,#tools:focus-within{opacity:1}
button{border:1px solid #ffffff55;border-radius:6px;background:#171a20;color:#fff;padding:7px 10px}
.landscape #screen{width:100vw;height:100dvh;object-fit:contain}
@media(orientation:landscape){main{width:100vw;height:100dvh}}
</style></head><body><main id="stage"><div id="name">$safeName</div><div id="tools"><button id="fullscreen" type="button">تمام‌صفحه</button></div><img id="screen" src="/stream" alt="تصویر زندهٔ گوشی"><div id="credit">created by k1 shadnafar</div></main>
<script>
(()=>{const stage=document.getElementById('stage'),img=document.getElementById('screen'),btn=document.getElementById('fullscreen');
const detect=()=>{if(img.naturalWidth&&img.naturalHeight){stage.classList.toggle('landscape',img.naturalWidth>img.naturalHeight);stage.classList.toggle('portrait',img.naturalHeight>=img.naturalWidth)}};
setInterval(detect,500);img.addEventListener('load',detect);
btn.addEventListener('click',()=>{if(!document.fullscreenElement){stage.requestFullscreen?.().catch(()=>{})}else{document.exitFullscreen?.()}});
})();
</script></body></html>"""
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
