# Microbit BLE Car

A Bluetooth gamepad for driving a micro:bit Cutebot robot car.

<img width="550" src="https://github.com/user-attachments/assets/ff06b313-6f5a-4fda-a942-0b4094d82a32" />

<img width="550" src="https://github.com/user-attachments/assets/50f2d146-8774-4cb2-b6f5-f9bcaeba6356" />

## Controls
You can control the car either from an Android app or from a modern web browser that supports Bluetooth.
The controller offers two layouts: a **single direction pad** or **two analogue sticks** (one for forward and reverse, one for steering).
### Buttons
* A: Turn on the car's front LEDs
* B: Horn
* C: Toggle the LEDs color


### Android app
<img width="550" src="https://github.com/user-attachments/assets/2619fb78-8ed0-4427-a806-d9656210688e" />
<img width="550" src="https://github.com/user-attachments/assets/c56081d6-6fc7-405b-8679-63725727c410" />

### Web browser
Note that Web Bluetooth is **not supported by every browser**: the web page needs Chrome, Edge or Opera, and must be served over HTTPS or from localhost.

<img width="1154" src="https://github.com/user-attachments/assets/ab835160-11e0-4705-b8f1-f1928048d3a1" />
<img width="1154" src="https://github.com/user-attachments/assets/bcf043eb-b7c4-4b6a-be3c-990b4ef1992a" />


## Structure
* `web/`: the web page you can host yourself
* `android/`: the Android app sources, ready to compile
* `microbit/`: the JavaScript micro:bit code you need to upload to your board


## Requirements

**Web page**
- Nothing to build — just a single HTML page using CSS and JavaScript
- Serve `web/` over HTTPS (or open it on `localhost`) in a browser with Web Bluetooth

**Android app**
- JDK 17 or newer (the JBR bundled with Android Studio works well)
- Android SDK with platform 37 — `compileSdk` 37, `minSdk` 26
- Gradle 9.7.1, which the included wrapper downloads for you

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**micro:bit program**
- Paste `microbit/microbit-makecode.ts` into [makecode.microbit.org](https://makecode.microbit.org) with the editor set to JavaScript
- Add the `bluetooth` and `cutebot` extensions

## Credits
The analogue sticks on the web page use [bobboteck/JoyStick](https://github.com/bobboteck/JoyStick) (MIT), vendored as `web/joy.js` with two local changes marked inline.
