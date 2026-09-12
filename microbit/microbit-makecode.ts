/**
 * micro:bit (Cutebot) program for this project's web and Android controllers.
 *
 * Paste it into makecode.microbit.org with the editor set to JavaScript
 * (required extensions: "bluetooth" and "cutebot").

 * Protocol:
 *   <DIR>,<SPEED>      analogue joystick position, held until the next command.
 *                      DIR is N, NE, E, SE, S, SW, W, NW or C (centre), and
 *                      SPEED is 0..100 — how far the stick is from the centre.
 *                      "C,0" means the stick is home, so: stop.
 *   A | B | C          actions (LED on/off, horn, LED colour)
 *
 * A joystick command always carries a comma and an action never does, which is
 * what tells the centre position "C,0" apart from the colour button "C".
 *
 * Mixing the two wheel speeds is this program's job: the controller only says
 * where the stick is, and every turn below picks the pair of speeds for it.
 */

bluetooth.startUartService()


const WATCHDOG_MS = 600   // must be longer than the page heartbeat (350 ms)

let lastCommandAt = 0
let driving = false      // only true after an actual motor command

/* How the eight directions become a pair of wheel speeds. SPEED is the outer
   wheel; these two say what the other wheel does.
     TURN_INNER  the inside wheel on a diagonal: still driving forward, just
                 slower, which is what makes the car sweep a curve.
     SPIN        due east or west, with no forward or backward component at
                 all: the wheels turn opposite ways and the car pivots on the
                 spot. Kept below 100% because a spin needs less speed to read
                 as deliberate. */
const TURN_INNER = 35    // % of SPEED given to the inside wheel on a diagonal
const SPIN = 75          // % of SPEED used by both wheels when spinning in place

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

/**
 * One branch per stick position. `speed` is how hard the stick is pushed, so
 * the same direction is gentle near the centre and full tilt at the rim.
 */
function drive(direction: string, speed: number) {
    if (speed < 0) speed = 0
    if (speed > 100) speed = 100

    const inner = Math.round(speed * TURN_INNER / 100)
    const spin = Math.round(speed * SPIN / 100)

    let left = 0
    let right = 0

    if (direction == "N") {
        // straight ahead: both wheels together
        left = speed
        right = speed
    } else if (direction == "NE") {
        // forward and curving right: the right wheel is the slow, inside one
        left = speed
        right = inner
    } else if (direction == "E") {
        // pivot clockwise on the spot
        left = spin
        right = -spin
    } else if (direction == "SE") {
        // backing up along the same curve as NE, so the car retraces it
        left = -speed
        right = -inner
    } else if (direction == "S") {
        // straight back
        left = -speed
        right = -speed
    } else if (direction == "SW") {
        left = -inner
        right = -speed
    } else if (direction == "W") {
        // pivot anticlockwise on the spot
        left = -spin
        right = spin
    } else if (direction == "NW") {
        // forward and curving left: now the left wheel is the inside one
        left = inner
        right = speed
    } else {
        // "C", or anything unexpected: the stick is home
        stopCar()
        return
    }

    if (left == 0 && right == 0) {
        stopCar()
        return
    }

    cuteBot.motors(left, right)
    driving = true
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

    if (command.indexOf(",") >= 0) {
        // "<DIR>,<SPEED>" — must be tested before the single-letter actions,
        // or "C,0" would be mistaken for the colour button.
        const parts = command.split(",")
        if (parts.length >= 2) {
            drive(parts[0], Math.round(parseFloat(parts[1])))
        }
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
