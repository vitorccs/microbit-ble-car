package org.microbit.carjoystick

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

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
    dimmed: Boolean = false,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            /* The CSS pressed state drops the button by a few pixels and takes
               its bottom shadow away; the offset alone reads the same here. */
            .offset(y = if (active) 4.dp else 0.dp)
            .alpha(if (dimmed) 0.5f else 1f)
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

/* ================================= D-pad ================================= */

@Composable
fun DPad(
    held: Set<Command>,
    scale: Float,
    onPress: (Command) -> Unit,
    onRelease: (Command) -> Unit,
) {
    val keySize = (62 * scale).dp
    val shape = RoundedCornerShape(14.dp)

    @Composable
    fun Key(command: Command, glyph: String) = PadButton(
        label = glyph,
        color = Palette.dpad,
        active = command in held,
        shape = shape,
        size = keySize,
        fontSize = (22 * scale).sp,
        onPress = { onPress(command) },
        onRelease = { onRelease(command) },
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Key(Command.UP, "▲")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Key(Command.LEFT, "◀")
            Spacer(Modifier.size(keySize))
            Key(Command.RIGHT, "▶")
        }
        Key(Command.DOWN, "▼")
    }
}

/* =============================== Action pad =============================== */

@Composable
fun ActionPad(
    flashing: Set<Command>,
    scale: Float,
    onPress: (Command) -> Unit,
) {
    val keySize = (66 * scale).dp

    @Composable
    fun Action(command: Command, color: Color, dimmed: Boolean = false) = PadButton(
        label = command.name,
        color = color,
        active = command in flashing,
        shape = CircleShape,
        size = keySize,
        fontSize = (23 * scale).sp,
        dimmed = dimmed,
        onPress = { onPress(command) },
        onRelease = { },
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Action(Command.C, Palette.actionC)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Action(Command.A, Palette.actionA)
            Spacer(Modifier.size(keySize))
            Action(Command.B, Palette.actionB)
        }
        /* D has no branch in the MakeCode program yet. */
        Action(Command.D, Palette.actionD, dimmed = true)
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

/* ================================ Speed ================================= */

@Composable
fun SpeedSlider(speed: Int, scale: Float, onChange: (Int) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width((170 * scale).dp),
    ) {
        Text(
            text = "VELOCIDADE  $speed",
            color = Palette.muted,
            fontSize = (11 * scale).sp,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = speed.toFloat(),
            onValueChange = { onChange((it / 5).roundToInt() * 5) },
            valueRange = 20f..100f,
            steps = 15,   // 20..100 in steps of 5 is 17 stops, so 15 in between
            colors = SliderDefaults.colors(
                thumbColor = Palette.blue,
                activeTrackColor = Palette.blue,
                inactiveTrackColor = Palette.panelDark,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
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
            Text("REGISTRO  ·  toque fora para fechar", color = Palette.muted, fontSize = 11.sp)
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
            Text("Escolha o seu micro:bit", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (devices.isEmpty()) "Procurando..." else "${devices.size} encontrado(s)",
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
        text = "Segure para andar  ·  combine duas setas para curvar  ·  " +
            "teclado: setas ou WASD para dirigir, J K L para A B C",
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

