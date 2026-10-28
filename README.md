# Android Phone Cast

Experimental Android screen-casting sender with a browser-based receiver hosted by the phone, plus a native Android TV / Android TV Box receiver.

## Phone sender
Build the sender APK from the `app` module and start screen sharing. The phone displays its local IP address and HTTP port.

## Native TV receiver
Build `receiver-tv` and install `receiver-tv/build/outputs/apk/debug/receiver-tv-debug.apk` on the Android TV / TV Box. Keep the phone and TV on the same Wi-Fi network. Enter the sender address shown in PhoneCast, including the port (for example `192.168.1.20:8080`), then select **اتصال به PhoneCast**.

The receiver consumes the existing MJPEG `/stream` endpoint directly, decodes JPEG frames natively, and does not use a browser/WebView. This reduces browser overhead, but actual latency and smoothness still depend on Wi-Fi quality, sender FPS, JPEG quality, resolution and TV hardware.

## Build
GitHub Actions builds both APKs with JDK 17 and Android SDK 35.
