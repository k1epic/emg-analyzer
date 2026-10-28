package com.k1epic.phonecast.tv

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android TV receiver for the existing PhoneCast MJPEG endpoint.
 * It decodes JPEG frames directly and avoids the browser DOM/JavaScript overhead.
 */
class TvReceiverActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val connected = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
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
            contentDescription = "PhoneCast live screen"
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))

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
        val endpoint = if (host.contains("/")) "http://$host/stream" else "http://$host/stream"
        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        connected.set(true)
        addressInput.isEnabled = false
        connectButton.text = "قطع اتصال"
        status.text = "در حال اتصال…"
        worker.execute {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Accept", "multipart/x-mixed-replace")
                }
                connection = conn
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException(when (conn.responseCode) {
                        409 -> "PhoneCast در حال حاضر به گیرندهٔ دیگری متصل است."
                        503 -> "ابتدا انتقال تصویر را در گوشی شروع کنید."
                        else -> "پاسخ سرور: HTTP ${conn.responseCode}"
                    })
                }
                main.post { status.text = "متصل شد؛ دریافت تصویر زنده فعال است." }
                BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                    readJpegFrames(input) { bytes ->
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) main.post {
                            if (connected.get()) {
                                image.setImageBitmap(bitmap)
                                if (panel.visibility == View.VISIBLE) panel.visibility = View.GONE
                            }
                        }
                    }
                }
                if (connected.get()) throw IllegalStateException("جریان تصویر قطع شد.")
            } catch (e: Exception) {
                if (connected.get()) main.post {
                    status.text = "اتصال ناموفق: ${e.message ?: "خطای شبکه"}"
                    panel.visibility = View.VISIBLE
                    disconnectUiOnly()
                }
            } finally {
                connection?.disconnect()
                connection = null
            }
        }
    }

    /** Extracts JPEG frames from the multipart stream by JPEG SOI/EOI markers. */
    private fun readJpegFrames(input: BufferedInputStream, onFrame: (ByteArray) -> Unit) {
        var previous = -1
        var capturing = false
        var frame = ByteArrayOutputStream(256 * 1024)
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
                // Avoid runaway memory if the stream is malformed.
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
        connection?.disconnect()
        connection = null
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
        connection?.disconnect()
        worker.shutdownNow()
        super.onDestroy()
    }
}
