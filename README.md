# Android Phone Cast

Experimental Android screen-casting sender with a browser-based MJPEG receiver and a native Android TV / Android TV Box receiver.

## Phone sender
Build the sender APK from the `app` module and start screen sharing. H.264 hardware encoding is enabled by default and sends the screen directly from the MediaProjection virtual display to a MediaCodec encoder Surface. Open **تنظیمات** to switch back to MJPEG for the legacy browser receiver, adjust the frame rate, capture width, and H.264 bitrate.

## Native TV receiver
Build `receiver-tv` and install `receiver-tv/build/outputs/apk/debug/receiver-tv-debug.apk` on Android TV / TV Box. Keep the phone and TV on the same Wi-Fi network. Enter the sender address shown in PhoneCast, including the port (for example `192.168.1.20:8080`), then select **اتصال به PhoneCast**.

The native receiver consumes the sender's framed H.264/AVC endpoint and decodes it with Android MediaCodec. It uses low-latency decode mode on Android 11+ when supported, preserves the video aspect ratio, and reconfigures the decoder when landscape/portrait rotation changes the stream dimensions. If H.264 is disabled in the sender settings, the receiver automatically falls back to the existing MJPEG endpoint.

## H.264 transport
The private local-network `/h264` endpoint sends length-prefixed packets containing payload size, codec flags, width, height, presentation timestamp, and the AVC access unit. Codec configuration (SPS/PPS) and key frames are forwarded to let the receiver initialize and recover after reconnects. Use only on a trusted local network; the HTTP stream itself is not encrypted.

## Build and verification
GitHub Actions builds both APKs with JDK 17 and Android SDK 35. The workflow verifies package signatures and ZIP integrity, but real playback performance still needs to be checked on the intended phone and TV Box hardware.
