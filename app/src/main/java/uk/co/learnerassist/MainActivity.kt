package uk.co.learnerassist

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.learnerassist.core.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: DashboardViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) model.connectLive(true) else model.permissionDenied()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF9FDAC7), background = Color(0xFF101818), surface = Color(0xFF1B2929),
                onSurface = Color(0xFFE5F0EB), secondary = Color(0xFFEDC881))) {
                val state by model.state.collectAsStateWithLifecycle()
                LearnerAssist(state, model) {
                    val needed = buildList {
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        else { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
                        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
                    }
                    permissions.launch(needed.toTypedArray())
                }
            }
        }
    }
    override fun onStart() { super.onStart(); model.foreground() }
    override fun onStop() { model.background(); super.onStop() }
}

@Composable
private fun LearnerAssist(state: DashboardState, model: DashboardViewModel, connect: () -> Unit) {
    var page by rememberSaveableState()
    var confirmLive by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("Drive", "Simulator", "Settings", "Review").forEachIndexed { index, label ->
                    NavigationBarItem(selected = page == index, onClick = { page = index; if (index == 3) model.review() },
                        icon = { Text(listOf("◉", "≈", "⚙", "≡")[index], fontSize = 22.sp) }, label = { Text(label) })
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("LEARNERASSIST", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 3.sp)
            when (page) {
                0 -> Dashboard(state, model) { confirmLive = true }
                1 -> SimulatorScreen(state, model)
                2 -> SettingsScreen(state, model)
                3 -> ReviewScreen(state, model)
            }
            if (state.note.isNotBlank()) Text(state.note, color = MaterialTheme.colorScheme.secondary)
            Text("Advisory teaching aid. The instructor remains responsible. Road conditions, hazards and clutch position cannot be reliably inferred from OBD data.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmLive) AlertDialog(onDismissRequest = { confirmLive = false },
        title = { Text("Enable real vehicle monitoring?") },
        text = { Text("Only proceed with the reader available and an authorised operator present. Confirm the adapter address and port first. Only the approved read-only sensor requests and temporary interface settings are used. Read-only validation cannot prevent every adapter or firmware fault. Configure while parked; instructor/passenger operation only.") },
        confirmButton = { TextButton(onClick = { confirmLive = false; connect() }) { Text("Enable and connect") } },
        dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Cancel") } })
}

@Composable private fun rememberSaveableState(): MutableState<Int> = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }

@Composable private fun Dashboard(s: DashboardState, model: DashboardViewModel, connect: () -> Unit) {
    Text("A calmer way to learn", style = MaterialTheme.typography.headlineMedium)
    Surface(color = if (s.simulation) Color(0xFF51452E) else Color(0xFF224B40), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(if (s.simulation) "SIMULATED DATA" else if (s.reading == null) "LIVE MODE · NO CURRENT DATA" else "LIVE ECU DATA · READ ONLY", fontWeight = FontWeight.Bold)
            Text(s.status, style = MaterialTheme.typography.bodySmall)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ENGINE SPEED", style = MaterialTheme.typography.labelLarge)
            Text(s.reading?.rpm?.let { "%.0f".format(Locale.UK, it) } ?: "—", fontSize = 72.sp, fontWeight = FontWeight.Light)
            Text("rpm", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Metric("SPEED", s.reading?.mph?.let { "%.0f mph".format(Locale.UK, it) } ?: "—")
                Metric("EST. GEAR", s.gear?.toString() ?: "Unknown")
            }
            Spacer(Modifier.height(16.dp))
            Text("Engine load: ${s.reading?.load?.let { "%.0f%%".format(Locale.UK, it) } ?: "Unavailable"}")
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = Color(0xFF243C37)) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Text("${s.settings.coaching.mode.name.lowercase().replaceFirstChar { it.uppercase() }} coaching", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(s.advice.text, style = MaterialTheme.typography.headlineSmall)
            if (s.advice == Advice.FIRST) Text("For very slow progress if continuing. If stopping, follow your instructor's stopping routine.", style = MaterialTheme.typography.bodyMedium)
            Text(if (s.simulation) "Demonstration gear ratios · not your vehicle calibration" else "Estimated from calibrated RPM / speed. Clutch and neutral are not directly sensed.", style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.simulation) Button(onClick = { if (s.running) model.stop() else model.foreground() }) { Text(if (s.running) "Pause demo" else "Resume demo") }
        else if (s.running) Button(onClick = model::stop) { Text("Disconnect") }
        else Button(onClick = connect) { Text("Connect reader") }
    }
    Text(s.settings.vehicle, style = MaterialTheme.typography.bodyMedium)
}
@Composable private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun SimulatorScreen(s: DashboardState, model: DashboardViewModel) {
    Text("Practice without a vehicle", style = MaterialTheme.typography.headlineMedium)
    Toggle("Simulation mode", s.simulation, model::setSimulation)
    if (!s.simulation) { Text("Enable simulation to use these controls. This disconnects the reader."); return }
    Text("Demonstration scenarios", style = MaterialTheme.typography.titleMedium)
    Scenario.entries.forEach { scenario ->
        OutlinedButton(onClick = { model.scenario(scenario) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (!s.manual && s.scenario == scenario) "✓ ${scenario.title}" else scenario.title)
        }
    }
    Text("Manual sensor controls", style = MaterialTheme.typography.titleMedium)
    Text("Gear is estimated from these values using demo ratios. Changes in speed model acceleration/deceleration.")
    ValueSlider("Engine RPM", s.manualRpm.toFloat(), 0f..6000f) { model.manual(rpm = it.toDouble()) }
    ValueSlider("Speed (mph)", (s.manualKmh / 1.609344).toFloat(), 0f..70f) { model.manual(kmh = it * 1.609344) }
    ValueSlider("Engine load (%)", s.manualLoad.toFloat(), 0f..100f) { model.manual(load = it.toDouble()) }
    Toggle("Clutch disengaged / uncertain", s.clutch) { model.manual(clutch = it) }
}

@Composable private fun SettingsScreen(s: DashboardState, model: DashboardViewModel) {
    Text("Lesson settings", style = MaterialTheme.typography.headlineMedium)
    Text("Configure while parked. Live connection settings apply on the next connection.")
    val settings = s.settings
    Toggle("Simulation mode", s.simulation, model::setSimulation)
    SettingField("Vehicle profile", settings.vehicle) { model.updateSettings(settings.copy(vehicle = it)) }
    Text("Wi-Fi OBD connection", style = MaterialTheme.typography.titleLarge)
    SettingField("Reader Wi-Fi name (SSID)", settings.ssid) { model.updateSettings(settings.copy(ssid = it)) }
    SettingField("Verified adapter IPv4 address", settings.host, true) { model.updateSettings(settings.copy(host = it)) }
    SettingField("Verified TCP port", settings.port, true) { model.updateSettings(settings.copy(port = it)) }
    Text("The SWOBD3 manual names WiFi_OBDII but does not document IP, port or ELM version. Confirm them for your adapter; no default endpoint is assumed.", style = MaterialTheme.typography.bodySmall)
    Text("Coaching", style = MaterialTheme.typography.titleLarge)
    DrivingMode.entries.forEach { mode ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.coaching.mode == mode, onClick = { model.updateSettings(settings.copy(coaching = settings.coaching.copy(mode = mode))) })
            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
        }
    }
    Text("Intermediate waits longer and prompts less often. Independent keeps readings and logs with minimal assistance.")
    ValueSlider("Upshift sensitivity (RPM)", settings.coaching.upRpm.toFloat(), 1800f..4000f) {
        model.updateSettings(settings.copy(coaching = settings.coaching.copy(upRpm = it.toInt()))) }
    ValueSlider("Downshift sensitivity (RPM)", settings.coaching.downRpm.toFloat(), 900f..1600f) {
        model.updateSettings(settings.copy(coaching = settings.coaching.copy(downRpm = it.toInt()))) }
    ValueSlider("Minimum prompt interval (seconds)", settings.coaching.intervalSeconds.toFloat(), 10f..60f) {
        model.updateSettings(settings.copy(coaching = settings.coaching.copy(intervalSeconds = it.toInt()))) }
    Toggle("Spoken advisory prompts", settings.audio) { model.updateSettings(settings.copy(audio = it)) }
    ValueSlider("Voice volume (%)", settings.volume * 100f, 0f..100f) { model.updateSettings(settings.copy(volume = it / 100)) }
    Toggle("Save readings locally for review", settings.logging) { model.updateSettings(settings.copy(logging = it)) }
    Text("Logging is off by default. No location or account is collected. Local log is capped at approximately 1 MB; oldest data is cleared at the cap.")
    Text("Gear calibration", style = MaterialTheme.typography.titleLarge)
    Text("No Ford ratios are assumed. An instructor/passenger selects the known gear after at least 5 seconds at a steady speed above 9 mph, clutch fully engaged. Capture at least two gears. Never operate these controls as the driver.")
    (1..6).forEach { gear ->
        val ratio = settings.calibration.ratios[gear]
        OutlinedButton(onClick = { model.captureGear(gear) }, enabled = !s.simulation && s.running, modifier = Modifier.fillMaxWidth()) {
            Text("Capture gear $gear · ${ratio?.let { "%.1f rpm/(km/h)".format(Locale.UK, it) } ?: "Uncalibrated"}")
        }
    }
    Text(if (settings.calibration.valid()) "Vehicle calibration available" else "Gear estimation remains Unknown until calibration is valid.")
    OutlinedButton(onClick = { model.updateSettings(settings.copy(calibration = Calibration())) }) { Text("Clear vehicle calibration") }
    Text("Diagnostic status", style = MaterialTheme.typography.titleLarge)
    Text(s.status); Text(s.diagnostic)
    Text("Approved requests: 0100, 010C, 010D, 0104. Temporary interface setup: ATI, ATE0, ATL0, ATH0, ATTP0. No command console or ECU modification functions.", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun ReviewScreen(s: DashboardState, model: DashboardViewModel) {
    Text("Lesson review", style = MaterialTheme.typography.headlineMedium)
    Text("Last 20 saved samples · time, source, RPM, km/h, load, estimated gear, advice. Simulation and live samples are labelled separately.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = model::review) { Text("Refresh") }
        OutlinedButton(onClick = model::deleteLog) { Text("Delete log") }
    }
    if (s.review.isEmpty()) Text("No saved readings. Enable local logging in Settings to review a lesson.")
    s.review.reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}
@Composable private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange)
    }
}
@Composable private fun SettingField(label: String, value: String, numeric: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text), modifier = Modifier.fillMaxWidth())
}
@Composable private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Column { Text("$label: ${value.toInt()}"); Slider(value = value, onValueChange = change, valueRange = range) }
}
