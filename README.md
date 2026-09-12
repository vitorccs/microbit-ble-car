# Microbit BLE Car

A Bluetooth gamepad for driving a micro:bit Cutebot robot car.

## About

The project is the controller, in two interchangeable forms: a **web page**
(`web/`) and a native **Android app** (`android/`). Both draw the same gamepad,
speak the same protocol and talk to the same micro:bit program
(`microbit/microbit-makecode.ts`) — pick whichever suits the device in your
hands.

The controller offers two layouts, swapped with the centre button: **two
analogue sticks**, one for forward and reverse and one for steering, or a
**single direction pad** with its own speed setting. Either way the page only
says which of the nine directions the car should go and how hard; the micro:bit
works out what each wheel does with that.

Note that Web Bluetooth is **not supported by every browser**: the web page
needs Chrome, Edge or Opera, and must be served over HTTPS or from localhost.
Firefox and Safari cannot connect at all — that is what the Android app is
there for.

## Requirements

**Web page** — nothing to build. Serve `web/` over HTTPS (or open it on
`localhost`) in a browser with Web Bluetooth.

**Android app**

- JDK 17 or newer (the JBR bundled with Android Studio does nicely)
- Android SDK with platform 37 — `compileSdk` 37, `minSdk` 26
- Gradle 9.7.1, which the included wrapper downloads by itself

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**micro:bit program** — paste `microbit/microbit-makecode.ts` into
[makecode.microbit.org](https://makecode.microbit.org) with the editor set to
JavaScript, and add the `bluetooth` and `cutebot` extensions.

## Credits

The analogue sticks on the web page use
[bobboteck/JoyStick](https://github.com/bobboteck/JoyStick) (MIT), vendored as
`web/joy.js` with two local changes marked inline.
