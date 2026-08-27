# HEIMDALL

Simple Android nearby-signal scanner.

## What it does

- Automatically observes Bluetooth / BLE devices.
- Reads nearby Wi-Fi scan results exposed by Android.
- Shows Android-visible cellular network cells (LTE / 5G / etc.).
- Refresh cycle is approximately every 30 seconds.
- Keeps first-seen, last-seen and seen-count history while the app is open.
- Estimates Bluetooth and Wi-Fi distance from signal strength.
- Highlights very close, nearby and intermittent signals.
- Tap an entry to reveal MAC/BSSID, RSSI and other technical details.
- One Pause / Resume button. There is no manual Scan button.

## Important cellular limitation

Android does not expose raw neighboring handset LTE/5G transmissions to normal applications. The Cellular section therefore shows cell-network infrastructure observations available through Android's Telephony APIs. It does not claim that a nearby phone making a call has been detected.

## Build on Windows

1. Install Android Studio once so the Android SDK and bundled Java are available.
2. Double-click `buildapp.cmd`.
3. On the first run, the script downloads a private local Gradle copy into `.tools`.
4. The finished APK is copied to `HEIMDALL.apk` in the repository root.
5. If a USB-debugging Android phone is connected, the script offers to install the APK automatically.

The app targets Android 15 / API 35 and supports Android 8+ (API 26+).
