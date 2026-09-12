# Microbit BLE Car

A Bluetooth gamepad for driving a micro:bit Cutebot robot car.

## Controls
You can either control the car using an Android App or a modern Web Browser which supports Bluetooth.
The controller offers two layouts: a **single direction pad** or **two analogue sticks** (one for forward and reverse and one for steering).

### Android App
<img width="550" src="https://github.com/user-attachments/assets/2619fb78-8ed0-4427-a806-d9656210688e" />
<img width="550" src="https://github.com/user-attachments/assets/c56081d6-6fc7-405b-8679-63725727c410" />

### Web browser
Note that Web Bluetooth is **not supported by every browser**: the web page needs Chrome, Edge or Opera, and must be served over HTTPS or from localhost.

<img width="1154" src="https://github.com/user-attachments/assets/ab835160-11e0-4705-b8f1-f1928048d3a1" />
<img width="1154" src="https://github.com/user-attachments/assets/bcf043eb-b7c4-4b6a-be3c-990b4ef1992a" />


## Structure
* `web/`: The Web page file in which you can host 
* `android/`: here you can compile the Android App
* `microbit/`: Contains the JavaScript micro:bit code you need to upload to your board


## Requirements

**Web page** 
— Nothing to build, just a single HTML page using CSS and JavaScript
- Serve `web/` over HTTPS (or open it on `localhost`) in a browser with Web Bluetooth.

**Android app**
- JDK 17 or newer (the JBR bundled with Android Studio does nicely)
- Android SDK with platform 37 — `compileSdk` 37, `minSdk` 26
- Gradle 9.7.1, which the included wrapper downloads by itself

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**micro:bit program** 
— Paste `microbit/microbit-makecode.ts` into [makecode.microbit.org](https://makecode.microbit.org) with the editor set to JavaScript
- Add the `bluetooth` and `cutebot` extensions.

## Credits
The analogue sticks on the web page use [bobboteck/JoyStick](https://github.com/bobboteck/JoyStick) (MIT), vendored as `web/joy.js` with two local changes marked inline.
