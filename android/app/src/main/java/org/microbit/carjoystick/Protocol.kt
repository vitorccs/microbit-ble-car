package org.microbit.carjoystick

import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Nordic UART Service, as exposed by MakeCode's bluetooth.startUartService().
 * The micro:bit reads with uartReadUntil("\n"), so each command needs an LF.
 *
 * The micro:bit's UART profile is inverted relative to the usual Nordic UART
 * convention: 6e400002 is the micro:bit's TX (notify only) and 6e400003 is its
 * RX (write). Writing to 0002 fails with "GATT Error: Not supported".
 */
object Uart {
    val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // write:  app -> micro:bit
    val TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // notify: micro:bit -> app
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * Protocol (see microbit/microbit-makecode.ts):
 *
 *   <DIR>,<SPEED>      where the sticks are: DIR is one of N NE E SE S SW W NW
 *                      or C (centre), SPEED is 0..100 — how hard the driving
 *                      stick is pushed. Held until the next command.
 *   A | B | C          one-shot actions
 *
 * The app does not work out wheel speeds. It says which way the car should go
 * and the micro:bit decides what each wheel does with that.
 *
 * Two sticks, each locked to one axis, produce those nine directions between
 * them: the left one gives N, S or C and the right one E, W or C, and the two
 * names are concatenated. Splitting them across two thumbs is the whole point
 * — with a single pad, E and NE are neighbouring regions and a wobble turns a
 * curve into a spin.
 *
 * Note that "C" the colour button and "C,0" the centred sticks are different
 * commands; the micro:bit tells them apart by the comma, so no action command
 * may ever contain one. Speeds latch, and the app sends a heartbeat while
 * moving so the micro:bit's watchdog can stop the car if the link dies.
 */
object Protocol {
    const val HEARTBEAT_MS = 350L  // must stay well under the micro:bit's watchdog
    const val PUMP_MS = 100L       // heartbeat tick and retry after a failed write

    /** Stick home: the one motion command that means "stop". */
    const val STOP = "C,0"

    /** A key or gamepad button has no throw to measure, so it drives at a fixed
     *  push: brisk enough to be useful, short of full tilt. */
    const val KEY_SPEED = 70

    /** How far an axis must be pushed before it names a direction, as a fraction
     *  of the full throw. This is joy.js's rule, so the two controllers agree on
     *  where the dead zone ends. */
    const val DEAD_ZONE = 0.4f

    /** A motion command carries a comma and an action never does — the same test
     *  the micro:bit uses to tell "C,0" from the colour button "C". */
    fun isMotion(command: String): Boolean = command.contains(",")

    fun motion(direction: String, speed: Int): String =
        if (direction == "C") STOP else "$direction,$speed"

    /**
     * Where a stick pushed to (x, y) is pointing. Both are fractions of the full
     * throw on their own axis, with y positive upwards. The two axes are read
     * separately and their names concatenated, so up-and-right is "NE".
     */
    fun directionAt(x: Float, y: Float): String {
        val vertical = if (y > DEAD_ZONE) "N" else if (y < -DEAD_ZONE) "S" else ""
        val horizontal = if (x > DEAD_ZONE) "E" else if (x < -DEAD_ZONE) "W" else ""
        return (vertical + horizontal).ifEmpty { "C" }
    }

    /**
     * How hard the stick is pushed, 0..100: its distance from the centre. Each
     * axis is clamped on its own, so a corner push overshoots 100 and is capped.
     */
    fun speedAt(x: Float, y: Float): Int =
        (hypot(x, y) * 100f).roundToInt().coerceIn(0, 100)

    /**
     * The one place a direction name is built. Both steering sources — the two
     * sticks and the keyboard — reduce to a vertical name and a horizontal one,
     * and the nine directions are just those concatenated: "N" + "E" is "NE",
     * "" + "E" is "E", and nothing at all is "C".
     */
    fun combine(vertical: String, horizontal: String): String {
        val north = if (vertical == "N" || vertical == "S") vertical else ""
        val east = if (horizontal == "E" || horizontal == "W") horizontal else ""
        return (north + east).ifEmpty { "C" }
    }

    /** Held keys, reduced to the same pair of axis names a stick would give. */
    fun axesFor(held: Set<Command>): Pair<String, String> {
        val forward = (if (Command.UP in held) 1 else 0) - (if (Command.DOWN in held) 1 else 0)
        val side = (if (Command.RIGHT in held) 1 else 0) - (if (Command.LEFT in held) 1 else 0)

        return Pair(
            if (forward > 0) "N" else if (forward < 0) "S" else "",
            if (side > 0) "E" else if (side < 0) "W" else "",
        )
    }
}

/** Where one stick is right now, in the protocol's own terms. */
data class Stick(val direction: String = "C", val speed: Int = 0) {
    val isCentred: Boolean get() = direction == "C"
}

/** Which stick a movement came from. */
enum class Side { LEFT, RIGHT }

enum class Command {
    UP, DOWN, LEFT, RIGHT, A, B, C;

    val isDirection: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}
