package org.microbit.carjoystick

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
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
 * Protocol (see microbit-makecode.ts):
 *
 *   M,<left>,<right>   set both motor speeds, from -100 to 100, and keep
 *                      them running until the next command
 *   S                  stop
 *   A | B | C          one-shot actions
 *
 * Speeds latch, and the app sends a heartbeat while moving so the micro:bit's
 * watchdog can stop the car if the link dies.
 */
object Protocol {
    const val HEARTBEAT_MS = 350L  // must stay well under the micro:bit's watchdog
    const val PUMP_MS = 100L       // heartbeat tick and retry after a failed write
    const val TURN_RATIO = 0.7     // how much a sideways press biases the wheels
    const val SPIN_RATIO = 0.75    // speed used when turning in place

    const val STOP = "S"

    /** Commands the micro:bit has no branch for: they land in the empty `else`. */
    val UNHANDLED = setOf("D")

    fun isMotion(command: String): Boolean = command == STOP || command.startsWith("M")

    /**
     * Tank mixing: forward/back sets both wheels, left/right biases them. Pressing
     * a side on its own spins the car in place, which is what the old LEFT/RIGHT
     * commands did; pressing it together with UP or DOWN gives a real curve.
     */
    fun motionFor(held: Set<Command>, speed: Int): String {
        val forward = (if (Command.UP in held) 1 else 0) - (if (Command.DOWN in held) 1 else 0)
        val side = (if (Command.RIGHT in held) 1 else 0) - (if (Command.LEFT in held) 1 else 0)

        if (forward == 0 && side == 0) return STOP

        var left: Double
        var right: Double

        if (forward == 0) {
            left = side * speed * SPIN_RATIO
            right = -left
        } else {
            val bias = side * speed * TURN_RATIO
            left = forward * speed + bias
            right = forward * speed - bias

            /* Scale both wheels down together rather than clipping one of them,
               so the curve keeps its shape at full speed. */
            val peak = max(abs(left), abs(right))
            if (peak > 100) {
                left = left * 100 / peak
                right = right * 100 / peak
            }
        }

        return "M,${left.roundToInt()},${right.roundToInt()}"
    }
}

enum class Command {
    UP, DOWN, LEFT, RIGHT, A, B, C, D;

    val isDirection: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}
