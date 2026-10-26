package dev.gyrobridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

private val Mint = Color(0xFF8DE6CE)
private val Ink = Color(0xFF0D151C)
private val Panel = Color(0xFF17232D)
private val Muted = Color(0xFFA5B5C2)
private val colors = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink,
    surface = Panel, onSurface = Color(0xFFE7EFF4), onSurfaceVariant = Muted,
    secondaryContainer = Color(0xFF294438), onSecondaryContainer = Mint)

@Composable internal fun GyroScreen(action: (String) -> Unit, save: (SettingsChange) -> Unit) {
    MaterialTheme(colorScheme = colors) { AppScreen(action, save) }
}

@Composable private fun AppScreen(action: (String) -> Unit, save: (SettingsChange) -> Unit) {
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    val status by ServiceState.status.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GYROBRIDGE", style = MaterialTheme.typography.labelMedium, color = Mint)
                    Text("Aim with motion.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(if (status.connected) "SERVICE ON" else "SERVICE OFF", color = if (status.connected) Mint else Muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(12.dp))
                Switch(checked = status.connected || status.connecting, enabled = !status.connecting,
                    onCheckedChange = { action(if (it) GyroService.START else GyroService.STOP) })
            }
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                if (maxWidth >= 700.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(.9f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            StatusPanel(status, settings, action)
                            ActivationPanel(settings, status, action, save)
                            StartupPanel(settings, save)
                        }
                        Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            ModePanel(settings, save)
                            TuningPanel(settings, save)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        StatusPanel(status, settings, action)
                        ModePanel(settings, save)
                        ActivationPanel(settings, status, action, save)
                        TuningPanel(settings, save)
                        StartupPanel(settings, save)
                    }
                }
            }
        }
    }
}

@Composable private fun CardPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun Label(text: String) { Text(text, color = Muted, style = MaterialTheme.typography.labelMedium) }

@Composable private fun StatusPanel(s: BridgeStatus, c: GyroSettings, action: (String) -> Unit) {
    CardPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (s.held && s.running) Mint else Muted, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Text(when { s.error != null -> "Connection needs attention"; s.connecting -> "Connecting…"; s.binding -> "Listening for a button"; s.calibrating -> "Keep the Odin still…"; s.running && s.held -> "Gyro active"; s.running -> "Hold ${c.activationName} to aim"; s.connected -> "Connected • output paused"; else -> "Ready when you are" }, fontWeight = FontWeight.Medium)
        }
        Text(s.error ?: "${if (c.stick) "Right-stick" else "Mouse"} output · ${if (s.rate > 0) "${s.rate} Hz sensor" else "Odin 2 Portal"}", color = if (s.error != null) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall)
        if (c.stick && s.connected) {
            StickPreview(s.physicalX, s.physicalY, s.outX, s.outY)
            Text("Outline: physical stick   •   Dot: combined output", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.running && !s.connecting) Button(onClick = { action(GyroService.START) }) { Text(if (s.error == null) "Start service" else "Reconnect") }
            OutlinedButton(onClick = { action(GyroService.CALIBRATE) }, enabled = s.connected && !s.calibrating && !s.binding) { Text("Calibrate gyro") }
        }
        if (s.calibrating) Text("Leave the device still for 2 seconds. Movement restarts calibration.", color = Mint, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun StickPreview(px: Float, py: Float, x: Float, y: Float) {
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val radius = size.height * .4f
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF263744), radius, center)
        drawLine(Color(0xFF40515D), center - Offset(radius, 0f), center + Offset(radius, 0f))
        drawLine(Color(0xFF40515D), center - Offset(0f, radius), center + Offset(0f, radius))
        drawCircle(Color(0xFF95A5B0), 6.dp.toPx(), center + Offset(px * radius, py * radius), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawCircle(Mint, 4.dp.toPx(), center + Offset(x * radius, y * radius))
    }
}
@Composable private fun ModePanel(c: GyroSettings, save: (SettingsChange) -> Unit) {
    CardPanel {
        Label("OUTPUT MODE")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = !c.stick, onClick = { save { current -> current.copy(stick = false) } }, label = { Text("Mouse") })
            FilterChip(selected = c.stick, onClick = { save { current -> current.copy(stick = true) } }, label = { Text("Right stick") })
        }
        Text(if (c.stick) "Gyro adds to your physical right stick. Select GyroBridge Controller in games that ask for a controller."
            else "Relative mouse movement for apps and games with mouse support.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun ActivationPanel(c: GyroSettings, s: BridgeStatus, action: (String) -> Unit, save: (SettingsChange) -> Unit) {
    CardPanel {
        Label("HOLD TO ACTIVATE")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.activationName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { action(if (s.binding) GyroService.CANCEL_BIND else GyroService.BIND) }, enabled = !s.connecting) { Text(if (s.binding) "Cancel" else "Press to assign") }
        }
        Text(if (s.binding) "Press and release a grip key, trigger, or other controller button."
            else "Assign a grip key, trigger, or other controller button. Hold it while moving the Odin to aim.", style = MaterialTheme.typography.bodySmall, color = if (s.binding) Mint else Muted)
        if (c.activationType == 1) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use button only for gyro", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = c.consume, onCheckedChange = { save { current -> current.copy(consume = it) } })
        }
        if (c.activationType == 3) Text("Analog triggers are also passed through to the game.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
@Composable private fun TuningPanel(c: GyroSettings, save: (SettingsChange) -> Unit) {
    CardPanel {
        Label("TUNING")
        TuningSlider("Smoothing time constant", c.smoothingMillis, 0f..40f, "%.1f ms", { save { current -> current.copy(smoothing = it / 40) } })
        Text("0 ms disables smoothing. Higher values reduce jitter and slow the response; this is not a fixed input delay.", style = MaterialTheme.typography.bodySmall, color = Muted)
        HorizontalDivider(color = Color(0xFF2B3B47))
        Label(if (c.stick) "STICK SENSITIVITY · % / °/s" else "MOUSE SENSITIVITY · COUNTS / °")
        if (c.stick) {
            TuningSlider("Horizontal", c.stickX, .1f..10f, "%.2f", { save { current -> current.copy(stickX = it) } })
            TuningSlider("Vertical", c.stickY, .1f..10f, "%.2f", { save { current -> current.copy(stickY = it) } })
        } else {
            TuningSlider("Horizontal", c.mouseX, .1f..60f, "%.1f", { save { current -> current.copy(mouseX = it) } })
            TuningSlider("Vertical", c.mouseY, .1f..60f, "%.1f", { save { current -> current.copy(mouseY = it) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Invert horizontal", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(c.invertX, { save { current -> current.copy(invertX = it) } })
            Spacer(Modifier.width(12.dp))
            Text("Invert vertical", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(c.invertY, { save { current -> current.copy(invertY = it) } })
        }
    }
}
@Composable private fun TuningSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, update: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(String.format(Locale.US, format, local), color = Mint, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = local.coerceIn(range), onValueChange = { local = it }, valueRange = range, onValueChangeFinished = { update(local) }, modifier = Modifier.fillMaxWidth())
    }
}
@Composable private fun StartupPanel(c: GyroSettings, save: (SettingsChange) -> Unit) {
    CardPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Start after boot", style = MaterialTheme.typography.bodyMedium)
                Text("Run the service after first unlock", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = c.boot, onCheckedChange = { save { current -> current.copy(boot = it) } })
        }
    }
}
