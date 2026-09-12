package org.microbit.carjoystick

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/* ============================ Building blocks ============================ */

/**
 * A button that reports press and release separately, so holding it keeps the
 * car moving. The gesture stays with this node once the finger is down, which
 * is what the web page got from setPointerCapture.
 */
@Composable
fun holdGestures(
    onPress: () -> Unit,
    onRelease: () -> Unit,
): Modifier {
    /* Keyed on Unit and reading the callbacks through rememberUpdatedState: keying
       on the lambdas themselves would restart pointerInput on every recomposition
       — including the one the press itself causes — and drop the release. */
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)

    return Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            press()
            waitForUpOrCancellation()
            release()
        }
    }
}

@Composable
private fun PadButton(
    label: String,
    color: Color,
    active: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    size: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            /* The CSS pressed state drops the button by a few pixels and takes
               its bottom shadow away; the offset alone reads the same here. */
            .offset(y = if (active) 4.dp else 0.dp)
            .clip(shape)
            .background(if (active) lighten(color) else color)
            .then(holdGestures(onPress, onRelease)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

private fun lighten(color: Color): Color = Color(
    red = (color.red + 0.12f).coerceAtMost(1f),
    green = (color.green + 0.12f).coerceAtMost(1f),
    blue = (color.blue + 0.12f).coerceAtMost(1f),
)

/* =============================== Joystick ================================ */

/** Which way a stick is allowed to move. The other axis is pinned to centre. */
enum class Axis { VERTICAL, HORIZONTAL }

/* Proportions of the control's overall size. They mirror joy.js's drawing, so
   the two controllers feel the same under the thumb — in particular the throw,
   which together with Protocol.DEAD_ZONE decides where each direction begins. */
private const val THROW_RATIO = 0.25f   // how far the knob's centre may travel
private const val KNOB_RATIO = 0.18f    // the knob's own radius
private const val RING_RATIO = 0.42f    // the circle marking full throw

/**
 * One analogue stick, locked to a single axis. It reports where it is pushed —
 * a cardinal direction and how far from the centre — and nothing about wheels:
 * the micro:bit works those out. Letting go recentres it, which is the only
 * "stop" the car ever gets from a driving gesture.
 *
 * The axis lock is the point of the whole design: with the left stick pinned to
 * the vertical and the right one to the horizontal, a sideways wobble while
 * driving forward moves nothing at all, so a curve can never become a spin by
 * accident. It takes letting go of one stick entirely to change that.
 */
@Composable
fun Joystick(
    diameter: Dp,
    axis: Axis,
    onMove: (direction: String, speed: Int) -> Unit,
) {
    val report by rememberUpdatedState(onMove)
    var knob by remember { mutableStateOf(Offset.Zero) }   // from the centre, in px
    val throwPx = with(LocalDensity.current) { diameter.toPx() } * THROW_RATIO

    /* The locked axis is pinned to zero before anything else looks at it, so the
       knob, the direction and the speed all agree that it never moved. */
    fun moveTo(raw: Offset) {
        val offset = Offset(
            if (axis == Axis.VERTICAL) 0f else raw.x.coerceIn(-throwPx, throwPx),
            if (axis == Axis.HORIZONTAL) 0f else raw.y.coerceIn(-throwPx, throwPx),
        )
        knob = offset

        val x = offset.x / throwPx
        val y = -offset.y / throwPx   // screen y grows downwards; the stick's does not
        val direction = Protocol.directionAt(x, y)
        report(direction, if (direction == "C") 0 else Protocol.speedAt(x, y))
    }

    Box(
        Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Palette.panelDark)
            /* Keyed on the two things `moveTo` reads from the composition:
               rekeying on every recomposition would drop the drag the movement
               itself caused. */
            .pointerInput(diameter, axis) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                detectDragGestures(
                    onDragStart = { position -> moveTo(position - centre) },
                    onDrag = { change, _ ->
                        change.consume()
                        moveTo(change.position - centre)
                    },
                    onDragEnd = { moveTo(Offset.Zero) },
                    onDragCancel = { moveTo(Offset.Zero) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val span = size.minDimension
            val knobCentre = centre + knob
            val knobRadius = span * KNOB_RATIO

            drawCircle(
                color = Palette.stickRing,
                radius = span * RING_RATIO,
                center = centre,
                style = Stroke(width = 3.dp.toPx()),
            )

            /* A bar along each free axis — a cross when both are free — the
               counterpart of joy.js's internalDrawArrows: it says at a glance
               which way this stick is willing to move, so nobody fights the one
               that is locked. */
            val reach = span * RING_RATIO
            if (axis != Axis.HORIZONTAL) {
                drawLine(
                    color = Palette.stickRing,
                    start = Offset(centre.x, centre.y - reach),
                    end = Offset(centre.x, centre.y + reach),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            if (axis != Axis.VERTICAL) {
                drawLine(
                    color = Palette.stickRing,
                    start = Offset(centre.x - reach, centre.y),
                    end = Offset(centre.x + reach, centre.y),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Palette.blue, Palette.blueDark),
                    center = knobCentre,
                    radius = knobRadius,
                ),
                radius = knobRadius,
                center = knobCentre,
            )
            drawCircle(
                color = Palette.blueDark,
                radius = knobRadius,
                center = knobCentre,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/* ============================= Direction pad ============================= */

/* Proportions of the pad's overall size: the cross plate's two bars, then each
   arrow's own size and how far its centre sits from the pad's. They mirror the
   web page's CSS, so the two controllers look and feel the same. */
private const val PAD_BAR_LONG = 0.86f
private const val PAD_BAR_SHORT = 0.32f
private const val PAD_ARROW_SIZE = 0.17f
private const val PAD_ARROW_REACH = 0.31f

/** Which way each arrow points, as a rotation from "up". */
private val PAD_ARROWS = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)

/**
 * The direction pad, which replaces the left stick in the single-control mode.
 * It is a digital eight-way pad, not four separate buttons: the whole plate
 * tracks one finger and the sector it lands in names the direction, so a thumb
 * can slide from "up" into the corner and get NE without ever having to press
 * two buttons at once. Four buttons could not do that with one thumb, and
 * two-thumb diagonals are exactly what this mode is meant to avoid.
 *
 * The arrows are the labels for those sectors, and they light up to say which
 * way the car has been told to go — both of them on a diagonal.
 */
@Composable
fun DirectionPad(
    diameter: Dp,
    direction: String,
    onAim: (String) -> Unit,
) {
    val report by rememberUpdatedState(onAim)

    Box(
        Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Palette.panelDark)
            /* Not detectDragGestures: that one waits for touch slop before it
               says anything, and a tap held still is the pad's main gesture. */
            .pointerInput(diameter) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) / 2f

                fun aim(position: Offset) {
                    /* The pad counts y upwards; the screen counts it down. */
                    report(Protocol.sectorAt(position.x - centre.x, centre.y - position.y, radius))
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    aim(down.position)

                    /* The gesture stays with this finger until it lifts, so a
                       thumb that slides past the rim mid-corner keeps steering
                       instead of having its command dropped. */
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            pressed = false
                        } else {
                            aim(change.position)
                            change.consume()
                            pressed = change.pressed
                        }
                    }
                    report("C")
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val span = size.minDimension
            val long = span * PAD_BAR_LONG
            val short = span * PAD_BAR_SHORT
            val corner = CornerRadius(14.dp.toPx())

            /* The cross plate: purely the shape of a D-pad. The sectors are
               worked out from the angle, not from these bars. */
            listOf(Size(long, short), Size(short, long)).forEach { bar ->
                drawRoundRect(
                    color = Palette.padPlate,
                    topLeft = Offset(centre.x - bar.width / 2, centre.y - bar.height / 2),
                    size = bar,
                    cornerRadius = corner,
                )
            }

            val arrow = span * PAD_ARROW_SIZE
            val reach = span * PAD_ARROW_REACH

            PAD_ARROWS.forEach { (axis, degrees) ->
                rotate(degrees, centre) {
                    /* Drawn pointing up and rotated into place, so one path
                       shape serves all four. */
                    val path = Path().apply {
                        moveTo(centre.x, centre.y - reach - arrow / 2)
                        lineTo(centre.x + arrow / 2, centre.y - reach + arrow / 2)
                        lineTo(centre.x - arrow / 2, centre.y - reach + arrow / 2)
                        close()
                    }
                    drawPath(
                        path = path,
                        /* Both arrows light on a diagonal, which is what says NE
                           is one command and not a near miss between two. */
                        color = if (axis in direction) Palette.blue else Palette.padArrow,
                    )
                }
            }
        }
    }
}

/**
 * Direction and speed as they go on the wire. It sits next to the status line
 * but is not the same thing: the status only moves when a command is actually
 * sent, so this is what proves the sticks work before pairing.
 */
@Composable
fun StickReadout(command: String, scale: Float) {
    Text(
        text = command.replace(",", "  ·  "),
        color = if (command == Protocol.STOP) Palette.muted else Palette.blue,
        fontSize = (12 * scale).sp,
        fontWeight = FontWeight.Bold,
    )
}

/* ============================ Right stick + arc =========================== */

/** Where each button sits on the arc: degrees from 3 o'clock, growing
 *  clockwise, so 195°-255° is the upper-left quadrant — the way the right
 *  thumb travels when it pivots off the stick. Reaching outwards instead would
 *  go off the edge of the device. 30° apart, which is what keeps a clear gap
 *  between their rims: any closer and three buttons that size read as one
 *  blob. */
private val ARC_ANGLES = listOf(
    Triple(Command.A, 195.0, Palette.actionA),
    Triple(Command.B, 225.0, Palette.actionB),
    Triple(Command.C, 255.0, Palette.actionC),
)

/** |cos 195°| and |sin 255°| are both this: the arc overhangs the stick by the
 *  same amount to the left as it does upwards. */
private const val ARC_SPREAD = 0.966f

/** Action button diameter, as a fraction of the stick's. */
const val BUTTON_RATIO = 0.28f

private val ARC_GAP = 10.dp

/** With one stick the three buttons have no stick to orbit, so they close up
 *  into an even triangle instead: 120° apart, and a little bigger now that
 *  there is room. */
private val CLUSTER_ANGLES = listOf(
    Triple(Command.A, 210.0, Palette.actionA),
    Triple(Command.B, 330.0, Palette.actionB),
    Triple(Command.C, 90.0, Palette.actionC),
)

private const val CLUSTER_BUTTON_RATIO = 0.34f

/** How close the right stick comes to the edge of the panel. The arc reaches
 *  the other way, so this side needs no room for it — only enough not to look
 *  like it fell off. */
val STICK_EDGE_MARGIN = 6.dp

/**
 * How far past the stick's own edge the arc reaches, left and up. The caller
 * needs this to size the layout: `Modifier.offset` does not grow a parent, so
 * without the room reserved up front the panel's clip would slice a button off.
 */
fun arcReach(diameter: Dp): Dp {
    val button = diameter * BUTTON_RATIO
    val radius = diameter / 2 + button / 2 + ARC_GAP
    return radius * ARC_SPREAD + button / 2 - diameter / 2
}

/**
 * The right stick with A, B and C on an arc outside its ring, close enough for
 * the right thumb to reach without leaving the stick.
 *
 * The arc reaches left and up only, so only those two sides reserve room for
 * it; on the right the stick keeps just [STICK_EDGE_MARGIN] of clearance, which
 * is what puts it out by the edge of the device where the thumb already is.
 * Vertically the reserve stays symmetric, so the stick still lines up with the
 * left-hand one across the row.
 */
@Composable
fun RightStickWithActions(
    diameter: Dp,
    flashing: Set<Command>,
    onMove: (direction: String, speed: Int) -> Unit,
    onPress: (Command) -> Unit,
) {
    val reach = arcReach(diameter)

    Box(
        modifier = Modifier
            .width(diameter + reach + STICK_EDGE_MARGIN)
            .height(diameter + reach * 2),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ActionRing(
            diameter = diameter,
            angles = ARC_ANGLES,
            buttonSize = diameter * BUTTON_RATIO,
            radius = diameter / 2 + diameter * BUTTON_RATIO / 2 + ARC_GAP,
            flashing = flashing,
            onPress = onPress,
            modifier = Modifier.padding(end = STICK_EDGE_MARGIN),
        ) {
            Joystick(diameter = diameter, axis = Axis.HORIZONTAL, onMove = onMove)
        }
    }
}

/**
 * A, B and C alone, in an even triangle — what the right-hand side becomes once
 * the single-stick mode takes its stick away. The buttons grow a little now
 * that nothing is in the middle, and sit as close together as the arc's own gap
 * allows: on a 120° triangle adjacent centres are radius * sqrt(3) apart.
 */
@Composable
fun ActionCluster(
    diameter: Dp,
    flashing: Set<Command>,
    onPress: (Command) -> Unit,
) {
    val buttonSize = diameter * CLUSTER_BUTTON_RATIO
    val radius = (buttonSize + ARC_GAP) / sqrt(3f)

    Box(
        modifier = Modifier
            .width(radius * 2 + buttonSize + STICK_EDGE_MARGIN)
            .height(arcReach(diameter) * 2 + diameter),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ActionRing(
            diameter = radius * 2 + buttonSize,
            angles = CLUSTER_ANGLES,
            buttonSize = buttonSize,
            radius = radius,
            flashing = flashing,
            onPress = onPress,
            modifier = Modifier.padding(end = STICK_EDGE_MARGIN),
        )
    }
}

/**
 * The three action buttons placed polar-fashion around a centre, with whatever
 * belongs in that centre drawn underneath them. `Modifier.offset` does not grow
 * a parent, so the buttons overflow the box on purpose; the caller is the one
 * that reserves the room they land in.
 */
@Composable
private fun ActionRing(
    diameter: Dp,
    angles: List<Triple<Command, Double, Color>>,
    buttonSize: Dp,
    radius: Dp,
    flashing: Set<Command>,
    onPress: (Command) -> Unit,
    modifier: Modifier = Modifier,
    centre: @Composable () -> Unit = {},
) {
    val radiusPx = with(LocalDensity.current) { radius.toPx() }

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        centre()

        angles.forEach { (command, degrees, color) ->
            val radians = Math.toRadians(degrees)
            PadButton(
                label = command.name,
                color = color,
                active = command in flashing,
                shape = CircleShape,
                size = buttonSize,
                fontSize = (buttonSize.value * 0.38f).sp,
                modifier = Modifier.offset {
                    IntOffset(
                        (radiusPx * cos(radians)).roundToInt(),
                        (radiusPx * sin(radians)).roundToInt(),
                    )
                },
                onPress = { onPress(command) },
                onRelease = { },
            )
        }
    }
}

/* ============================ Bluetooth button ============================ */

@Composable
fun BluetoothButton(
    state: LinkState,
    scale: Float,
    onClick: () -> Unit,
) {
    val busy = state == LinkState.SCANNING || state == LinkState.CONNECTING
    val connected = state == LinkState.CONNECTED

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = Modifier
            .size((104 * scale).dp)
            .alpha(if (busy) pulse else 1f)
            .clip(CircleShape)
            .background(if (connected) Palette.green else Palette.blue)
            .border(3.dp, if (connected) Palette.greenDark else Palette.blueDark, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (connected) LIVE_GLYPH else IDLE_GLYPH,
            color = Color.White,
            fontSize = (38 * scale).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/* ============================ Setting buttons ============================ */

/**
 * One control or two. Deliberately smaller and duller than the Bluetooth
 * button: it is a setting, not the thing you came here to press. The label is
 * the number of controls it is showing right now.
 */
@Composable
fun ModeButton(
    singleStick: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    SettingButton(
        label = if (singleStick) "1" else "2",
        colour = Palette.text,
        scale = scale,
        fontScale = 19f,
        onClick = onClick,
    )
}

/**
 * How fast the pad drives, in percent, cycling through [Protocol.SPEED_LEVELS].
 * It only shows in the single-control mode: with two sticks the throw is the
 * throttle, so there would be nothing here to set. Three digits where the mode
 * button has one, so a size down; the blue says this one is about how the car
 * moves rather than how it is laid out.
 */
@Composable
fun SpeedButton(
    speed: Int,
    scale: Float,
    onClick: () -> Unit,
) {
    SettingButton(
        label = speed.toString(),
        colour = Palette.speedText,
        scale = scale,
        fontScale = 14f,
        onClick = onClick,
    )
}

@Composable
private fun SettingButton(
    label: String,
    colour: Color,
    scale: Float,
    fontScale: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size((48 * scale).dp)
            .clip(CircleShape)
            .background(Palette.panelDark)
            .border(3.dp, Palette.stickRing, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colour,
            fontSize = (fontScale * scale).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/* ================================ Log ==================================== */

@Composable
fun LogPanel(lines: List<String>, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.panelDark)
                .padding(16.dp),
        ) {
            Text("LOG  ·  tap outside to close", color = Palette.muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = lines.joinToString("\n"),
                color = Palette.text,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                modifier = Modifier.verticalScroll(scroll),
            )
        }
    }
}

/* ============================= Device picker ============================= */

@Composable
fun DevicePicker(
    devices: List<FoundDevice>,
    onPick: (FoundDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.7f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.panel)
                .padding(20.dp),
        ) {
            Text("Pick your micro:bit", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (devices.isEmpty()) "Searching..." else "${devices.size} found",
                color = Palette.muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.device.address }) { found ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.panelDark)
                            .clickable { onPick(found) }
                            .padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp)),
                    ) {
                        Text(found.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${found.device.address}   ${found.rssi} dBm",
                            color = Palette.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/* ================================ Misc =================================== */

@Composable
fun Legend(scale: Float) {
    Text(
        text = "Use the arrow keys (or WASD) and the J, K and L keys",
        color = Palette.muted,
        fontSize = (10 * scale).sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun LogToggle(onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Palette.panelDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", color = Palette.muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

