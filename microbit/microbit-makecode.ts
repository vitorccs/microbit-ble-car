/**
 * micro:bit (Cutebot) program for this project's web controller.
 *
 * Paste it into makecode.microbit.org with the editor set to JavaScript
 * (required extensions: "bluetooth" and "cutebot").

 * Protocol:
 *   M,<left>,<right>   speeds from -100 to 100, held until the next command
 *   S                  stop
 *   A | B | C          actions (LED on/off, horn, LED colour)
 *
 */

bluetooth.startUartService()


const WATCHDOG_MS = 600   // must be longer than the page heartbeat (350 ms)

let lastCommandAt = 0
let driving = false      // only true after an actual motor command

/* The horn is a raw PWM square wave, not the "music" extension: that
   extension's audio mixer fights the Bluetooth stack and panics the board.
   P0 drives the V2's built-in speaker, or a buzzer on the edge connector. */
const HORN_PERIOD_US = 1136   // 1 000 000 / 880 Hz
const HORN_DUTY = 300         // 0..1023 — volume; lower draws less current
const HORN_MS = 250           // how long one honk lasts

/* The RGB headlights: A turns them on and off, C walks through the palette.
   The colour is remembered while they are off, so turning them back on
   restores the last one picked. */
const LED_COLORS = [0xffff00, 0x0000ff, 0xff0000, 0x00ff00]   // yellow, blue, red, green
let ledColor = 0             // index into LED_COLORS
let ledOn = false

let wantedFace = IconNames.Asleep
let faceDirty = true
let hornPending = false
let hornOffAt = 0             // 0 = horn is off

function stopCar() {
    cuteBot.stopcar()
    driving = false
}

function applyLights() {
    if (ledOn) {
        cuteBot.colorLight(cuteBot.RGBLights.ALL, LED_COLORS[ledColor])
    } else {
        cuteBot.closeheadlights()
    }
}

function showFace(face: IconNames) {
    wantedFace = face
    faceDirty = true
}

bluetooth.onBluetoothConnected(function () {
    showFace(IconNames.Yes)
})

bluetooth.onBluetoothDisconnected(function () {
    stopCar()
    ledOn = false
    applyLights()
    showFace(IconNames.Asleep)
})

bluetooth.onUartDataReceived("\n", function () {
    const command = bluetooth.uartReadUntil("\n")
    lastCommandAt = input.runningTime()

    if (command.charAt(0) == "M") {
        // "M,<left>,<right>"
        const parts = command.split(",")
        if (parts.length >= 3) {
            cuteBot.motors(parseFloat(parts[1]), parseFloat(parts[2]))
            driving = true
        }
    } else if (command == "S") {
        stopCar()
    } else if (command == "A") {
        ledOn = !ledOn
        applyLights()
        showFace(ledOn ? IconNames.Happy : IconNames.Yes)
    } else if (command == "B") {
        showFace(IconNames.Angry)
        hornPending = true
    } else if (command == "C") {
        /* Colour only: the lights stay off if A left them off, and the new
           colour is what shows the next time they come on. */
        ledColor = (ledColor + 1) % LED_COLORS.length
        applyLights()
    }
})

// Effects: a single fiber draws the face and sounds the horn.
basic.forever(function () {
    if (faceDirty) {
        faceDirty = false
        basic.showIcon(wantedFace, 0)   // 0 = do not pause after drawing
    }
    if (hornPending) {
        hornPending = false             // clear FIRST: a new honk must not be lost
        pins.analogSetPeriod(AnalogPin.P0, HORN_PERIOD_US)
        pins.analogWritePin(AnalogPin.P0, HORN_DUTY)
        hornOffAt = input.runningTime() + HORN_MS
    } else if (hornOffAt != 0 && input.runningTime() >= hornOffAt) {
        hornOffAt = 0
        pins.analogWritePin(AnalogPin.P0, 0)   // pin off: silence
    }
    basic.pause(20)
})

// Watchdog: with no word from the controller, the car stops by itself.
basic.forever(function () {
    if (driving && input.runningTime() - lastCommandAt > WATCHDOG_MS) {
        stopCar()
        showFace(IconNames.No)
    }
    basic.pause(50)
})
