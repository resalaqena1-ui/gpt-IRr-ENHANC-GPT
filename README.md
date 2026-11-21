# IR Studio Cam — Android Studio project

A first Android implementation for a Xiaomi phone with an IR emitter and a Godox TT520 II optical-slave flash.

## Included in this prototype

- Camera2 live preview and JPEG capture.
- Runtime discovery of back-facing Camera2 camera IDs and logical-camera physical lens paths exposed by the phone.
- Manual ISO and shutter controls when the selected camera advertises `MANUAL_SENSOR`.
- Manual focus control when the selected lens advertises a non-zero minimum focus distance.
- Available white-balance presets, digital zoom, and JPEG saving to `DCIM/IRStudioCam`.
- IR test button and IR firing tied to the capture request, with a user-adjustable 0–1000 ms delay, before/after ordering, and 1–20 ms pulse width.
- Runtime query of the handset's supported IR carrier range; 38 kHz is selected when available.

## Important hardware notes

1. The app sends a generic modulated IR burst (default 38 kHz, 9 ms mark + 4.5 ms space; pulse width is adjustable from 1 to 20 ms). The flash firing in the user's practical test confirms that the handset's IR output can activate the optical sensor, but the exact Mi Remote waveform used in that test was not captured. The first install must be tested with the app's **Test IR** button. If the flash does not fire, the pulse profile must be adjusted to reproduce the successful remote command.
2. Camera2 capabilities are reported per camera/lens by the phone firmware. ISO, shutter, focus, RAW, and physical-lens switching are not guaranteed. The app enables manual controls only where Camera2 advertises support. This prototype saves JPEG; it does not yet write DNG/RAW.
3. The delay is software timing between sending the IR command and submitting the camera capture request. Android does not expose a hardware shutter-sync line for this setup, so the final offset must be calibrated on the actual phone/flash combination. The 1 ms slider steps are a setting resolution, not a guarantee of 1 ms physical accuracy.
4. The flash must be in S1 or S2, have a clear view of the phone's IR emitter, and be fully recycled before each shot. Keep the flash at a conservative power while calibrating.

## Build

Open this folder in a recent Android Studio installation (JDK 17, Android SDK Platform 35, Android Gradle Plugin 8.7.3 / Gradle 8.9), allow Gradle sync, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

The expected debug output after a successful Android Studio build is:

`app/build/outputs/apk/debug/app-debug.apk`

Minimum Android version: Android 10 (API 29).

## First-run test sequence

1. Install and grant Camera permission.
2. Open the lens selector and choose the back camera or a physical lens path exposed by the phone.
3. Put the TT520 II in S1 or S2 and aim the top IR emitter of the phone at its optical sensor.
4. Tap **Test IR**. Confirm the flash fires.
5. Start with a long exposure (e.g. 1/30 s), low flash power, and 0 ms delay. Take a test photo and adjust the before/after mode and delay in small increments.
6. Increase shutter speed only after the flash is consistently captured in the frame.

## Build verification status

This workspace did not have Android SDK/Gradle installed, so no APK was produced here and the project has not been installed on a physical Mi 10T Lite. The Kotlin source was checked for parser-level syntax errors, but a successful Android build and on-device test are still required before treating it as production-ready.
