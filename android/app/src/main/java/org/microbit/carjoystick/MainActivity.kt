package org.microbit.carjoystick

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Arrow keys, WASD and J K L, exactly as the web page maps them. */
private val KEY_MAP = mapOf(
    KeyEvent.KEYCODE_DPAD_UP to Command.UP,
    KeyEvent.KEYCODE_W to Command.UP,
    KeyEvent.KEYCODE_DPAD_DOWN to Command.DOWN,
    KeyEvent.KEYCODE_S to Command.DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT to Command.LEFT,
    KeyEvent.KEYCODE_A to Command.LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to Command.RIGHT,
    KeyEvent.KEYCODE_D to Command.RIGHT,
    KeyEvent.KEYCODE_J to Command.A,
    KeyEvent.KEYCODE_BUTTON_A to Command.A,
    KeyEvent.KEYCODE_K to Command.B,
    KeyEvent.KEYCODE_BUTTON_B to Command.B,
    KeyEvent.KEYCODE_L to Command.C,
    KeyEvent.KEYCODE_BUTTON_X to Command.C,
)

class MainActivity : ComponentActivity() {

    private lateinit var controller: CarController

    /** Asked for the first time the user taps the Bluetooth button. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            controller.startScan()
        } else {
            controller.setStatus("Bluetooth permission denied.", isError = true)
            controller.log("ERROR: permissions denied: " + granted.filterValues { !it }.keys.joinToString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        /* A joystick that loses a press to a swiped-in system bar is worse than
           useless, so the bars are hidden and only come back on a deliberate swipe. */
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent {
            controller = viewModel()
            ControllerScreen(
                controller = controller,
                onConnectRequest = { requestConnection() },
            )
        }
    }

    private fun requestConnection() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) controller.toggleConnection() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /* Physical keyboards and gamepads drive the car too. The repeat events are
       dropped: the pump does its own repeating. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!::controller.isInitialized) return super.onKeyDown(keyCode, event)
        val command = KEY_MAP[keyCode] ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            controller.press(command)
            buzz()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!::controller.isInitialized) return super.onKeyUp(keyCode, event)
        val command = KEY_MAP[keyCode] ?: return super.onKeyUp(keyCode, event)
        controller.release(command)
        return true
    }

    private fun buzz() = vibrate(this)
}

fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    runCatching {
        vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

@Composable
fun ControllerScreen(controller: CarController, onConnectRequest: () -> Unit) {
    val context = LocalContext.current

    val state by controller.state.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val readout by controller.readout.collectAsStateWithLifecycle()
    val flashing by controller.flashing.collectAsStateWithLifecycle()
    val logLines by controller.logLines.collectAsStateWithLifecycle()
    val showLog by controller.showLog.collectAsStateWithLifecycle()
    val devices by controller.devices.collectAsStateWithLifecycle()
    val picking by controller.picking.collectAsStateWithLifecycle()
    val singleStick by controller.singleStick.collectAsStateWithLifecycle()
    val pad by controller.pad.collectAsStateWithLifecycle()
    val speed by controller.speed.collectAsStateWithLifecycle()

    /* Leaving the app must not leave a button stuck down. */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller.releaseAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bg),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            /* One scale factor keeps the whole gamepad on screen on a small
               phone without a separate layout for it. */
            val scale = (maxHeight / 330.dp).coerceIn(0.62f, 1.15f)

            /* The sticks are sized to what is actually left over rather than to
               a fixed number. The right one is the demanding side: A, B and C
               ride an arc off its ring, and RightStickWithActions is symmetric
               so the panel's clip never cuts a button off — which costs the
               arc's reach on both sides of that stick.
               arcReach is linear in the diameter, so both fits are solved for
               rather than searched. Note the row is lopsided: the left stick
               takes `d`, the right group `d + arcReach(d) + STICK_EDGE_MARGIN`
               — the arc reaches left, so only that side reserves room for it.

               The single-stick mode leaves the right-hand side smaller still,
               but the sticks are sized the same way in both so that switching
               modes doesn't resize the stick under the thumb. */
            val centreColumn = (200 * scale).dp
            val slope = (arcReach(1.dp) - arcReach(0.dp)).value
            val gapTerm = arcReach(0.dp)

            // 2d + arcReach(d) + STICK_EDGE_MARGIN + centre <= maxWidth
            val byWidth =
                (maxWidth - centreColumn - STICK_EDGE_MARGIN - gapTerm) / (2 + slope)
            // the right group is square, so its height is that same span
            val byHeight = (maxHeight - gapTerm * 2) / (1 + slope * 2)

            val stickDiameter = minOf(byWidth, byHeight, 260.dp).coerceAtLeast(72.dp)

            Box(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    /* The panel takes the whole safe area: with the pads sized by
                       `scale` the leftover height becomes breathing room between
                       them instead of a band of empty background. */
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape((36 * scale).dp))
                    .background(Palette.panel)
                    .padding(horizontal = (26 * scale).dp, vertical = (16 * scale).dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.Center),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (singleStick) {
                        DirectionPad(
                            diameter = stickDiameter,
                            direction = pad,
                            /* A tick on every new direction, the way a real
                               D-pad clicks — but not on the release, which is
                               the finger leaving rather than a command. */
                            onAim = { direction ->
                                if (direction != "C" && direction != pad) vibrate(context)
                                controller.onPadMoved(direction)
                            },
                        )
                    } else {
                        Joystick(
                            diameter = stickDiameter,
                            axis = Axis.VERTICAL,
                            onMove = { direction, moved ->
                                controller.onStickMoved(Side.LEFT, direction, moved)
                            },
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy((10 * scale).dp),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "MICRO:BIT\nCAR",
                            color = Palette.text.copy(alpha = 0.87f),
                            fontSize = (14 * scale).sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            lineHeight = (17 * scale).sp,
                        )

                        BluetoothButton(state = state, scale = scale, onClick = onConnectRequest)

                        Text(
                            text = status.text,
                            color = if (status.isError) Palette.red else Palette.muted,
                            fontSize = (11 * scale).sp,
                            textAlign = TextAlign.Center,
                        )

                        StickReadout(command = readout, scale = scale)

                        Row(
                            horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ModeButton(
                                singleStick = singleStick,
                                scale = scale,
                                onClick = { controller.toggleStickMode(); vibrate(context) },
                            )

                            /* Only the pad needs a throttle: with two sticks the
                               throw is the throttle. */
                            if (singleStick) {
                                SpeedButton(
                                    speed = speed,
                                    scale = scale,
                                    onClick = { controller.cycleSpeed(); vibrate(context) },
                                )
                            }
                        }
                    }

                    if (singleStick) {
                        ActionCluster(
                            diameter = stickDiameter,
                            flashing = flashing,
                            onPress = { controller.press(it); vibrate(context) },
                        )
                    } else {
                        RightStickWithActions(
                            diameter = stickDiameter,
                            flashing = flashing,
                            onMove = { direction, speed ->
                                controller.onStickMoved(Side.RIGHT, direction, speed)
                            },
                            onPress = { controller.press(it); vibrate(context) },
                        )
                    }
                }

                Box(Modifier.align(Alignment.TopEnd)) {
                    LogToggle(onClick = controller::toggleLog)
                }

                Column(Modifier.align(Alignment.BottomCenter)) {
                    Legend(scale)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }

        if (picking) {
            DevicePicker(
                devices = devices,
                onPick = controller::choose,
                onDismiss = controller::dismissPicker,
            )
        }

        if (showLog) {
            LogPanel(lines = logLines, onDismiss = controller::toggleLog)
        }
    }
}
