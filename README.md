# HEIMDALL

Simple Android nearby signal scanner.

## What it does

* Automatically observes Bluetooth and BLE devices.
* Requests fresh WiFi scans and discards cached results when Android does not confirm a successful new scan.
* Observes WiFi Direct peers only during the active discovery window.
* Requests current cellular information where Android supports it and rejects modem observations that are too old.
* Refreshes approximately once per minute and also has a manual Scan now button.
* Keeps first seen and repeated scan history while the app is open.
* Uses Close, Medium and Far signal categories for Bluetooth and WiFi instead of pretending RSSI is an exact physical distance.
* Sorts Bluetooth and WiFi observations by signal proximity category, then signal strength.
* Shows BSSID, radio address, RSSI, source age and technical details when available.
* Keeps RF dormant until supported SDR hardware is detected.

## Detection integrity

HEIMDALL does not create demo devices or synthetic scan results.

Every visible entry must belong to the current scan cycle. WiFi results are accepted only after Android reports a successful new scan and their hardware timestamp fits the current request window. Bluetooth LE batch results are timestamp checked. Cellular observations are timestamp checked. WiFi Direct results are accepted only during a current discovery session.

If a scanner fails, times out or returns only stale data, HEIMDALL reports the scanner warning and does not substitute old data as if it were current.

Names, SSIDs and radio addresses are observations supplied by devices or Android. They can be hidden, randomized, spoofed or otherwise misleading. A name such as `MFC` is therefore displayed as an advertised name, not proof that a particular brand or physical device has been identified.

Close, Medium and Far are based on received signal strength. They are not meter measurements and they do not prove physical distance. Cellular entries use Strong, Medium and Weak instead because cell signal strength must not be presented as tower distance.

## Important cellular limitation

Android does not expose raw neighboring handset LTE or 5G transmissions to normal applications. The Cellular section therefore shows cell network infrastructure observations available through Android Telephony APIs. It does not claim that a nearby phone making a call has been detected.

## Build on Windows

1. Install Android Studio once so the Android SDK and bundled Java are available.
2. Double click `buildapp.cmd`.
3. On the first run, the script downloads a private local Gradle copy into `.tools`.
4. The finished APK is copied to `HEIMDALL.apk` in the repository root.
5. If a USB debugging Android phone is connected, the script offers to install the APK automatically.

The app targets Android 15, API 35, and supports Android 8 and newer, API 26 and newer.
