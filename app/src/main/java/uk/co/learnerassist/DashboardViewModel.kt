package uk.co.learnerassist

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uk.co.learnerassist.core.*
import java.io.File

data class DashboardState(
    val settings: Settings = Settings(), val simulation: Boolean = true,
    val status: String = "Simulation · no vehicle connection", val diagnostic: String = "No hardware accessed",
    val reading: Reading? = null, val gear: Int? = null, val advice: Advice = Advice.WAIT,
    val coachingDecision: CoachingDecision = CoachingDecision(),
    val scenario: Scenario = Scenario.STEADY, val manual: Boolean = false,
    val manualRpm: Double = 2000.0, val manualKmh: Double = 40.0, val manualLoad: Double = 30.0,
    val clutch: Boolean = false, val running: Boolean = false, val note: String = "",
    val review: List<String> = emptyList(),
    val calibrationReadiness: Map<Int, CalibrationReadiness> = emptyMap(),
)
class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val mutable = MutableStateFlow(DashboardState(settings = store.load()))
    val state = mutable.asStateFlow()
    private val estimator = GearEstimator()
    private val coach = Coach()
    private val audio = AudioCoach(application)
    private var job: Job? = null
    private var watchdog: Job? = null
    @Volatile private var connection: ReaderConnection? = null
    private val samples = ArrayDeque<Reading>()
    private var lastLog = 0L
    private var sessionGeneration = 0L
    private val log = File(application.filesDir, "lesson.csv")
    private fun now() = SystemClock.elapsedRealtime()

    fun foreground() { if (state.value.simulation && !state.value.running) startSimulation() }
    fun background() { stop() }
    private fun clearSession() {
        estimator.reset(); coach.reset(); samples.clear(); audio.stop()
        mutable.update { it.copy(reading = null, gear = null, advice = Advice.WAIT, coachingDecision = CoachingDecision(), calibrationReadiness = emptyMap()) }
    }
    fun stop() {
        sessionGeneration++
        connection?.close(); connection = null
        job?.cancel(); job = null; watchdog?.cancel(); watchdog = null
        clearSession()
        mutable.update { it.copy(running = false, status = if (it.simulation) "Simulation paused" else "Disconnected · press Connect to start") }
    }
    fun setSimulation(enabled: Boolean) {
        stop(); mutable.update { it.copy(simulation = enabled, note = "") }
        if (enabled) startSimulation()
    }
    private fun startSimulation() {
        stop(); mutable.update { it.copy(running = true, status = "Simulation · no vehicle connection") }
        job = viewModelScope.launch {
            val start = now()
            while (isActive) {
                val s = state.value
                val time = now()
                val reading = if (s.manual) Reading(s.manualRpm, s.manualKmh, s.manualLoad, time, Source.SIMULATION, s.clutch)
                    else Simulator.sample(s.scenario, (time - start) / 1000.0, time)
                publish(reading)
                delay(500)
            }
        }
    }
    fun scenario(scenario: Scenario) {
        mutable.update { it.copy(scenario = scenario, manual = false) }
        if (state.value.simulation) startSimulation()
    }
    fun manual(rpm: Double? = null, kmh: Double? = null, load: Double? = null, clutch: Boolean? = null) {
        mutable.update { it.copy(manual = true, manualRpm = rpm ?: it.manualRpm, manualKmh = kmh ?: it.manualKmh,
            manualLoad = load ?: it.manualLoad, clutch = clutch ?: it.clutch) }
    }
    fun updateSettings(settings: Settings) {
        val previous = state.value.settings
        val calibrationChanged = previous.calibration != settings.calibration
        val coachingChanged = previous.coaching != settings.coaching
        store.save(settings)
        if (calibrationChanged) estimator.reset()
        if (calibrationChanged || coachingChanged) coach.reset()
        if (previous.audio != settings.audio || previous.volume != settings.volume || calibrationChanged || coachingChanged) audio.stop()
        mutable.update { it.copy(settings = settings, gear = if (calibrationChanged) null else it.gear,
            advice = if (calibrationChanged || coachingChanged) Advice.WAIT else it.advice,
            coachingDecision = if (calibrationChanged || coachingChanged) CoachingDecision() else it.coachingDecision) }
    }
    fun permissionDenied() { mutable.update { it.copy(note = "Nearby Wi-Fi/local network permission is required. Enable it in Android app settings and retry.") } }

    /** Only called after the user confirms a dedicated live-access dialog. Never persisted. */
    fun connectLive(explicitlyActivated: Boolean) {
        if (!explicitlyActivated || state.value.simulation) return
        stop()
        val settings = state.value.settings
        val port = settings.port.toIntOrNull()
        if (settings.host.isBlank() || port == null || port !in 1..65535 || settings.ssid.isBlank()) {
            mutable.update { it.copy(note = "Enter the verified adapter SSID, IPv4 address and TCP port in Settings first.") }; return
        }
        mutable.update { it.copy(running = true, status = "Select reader Wi-Fi…", note = "") }
        val generation = sessionGeneration
        job = viewModelScope.launch {
            val reader = ReaderConnection(getApplication())
            connection = reader
            try {
                val network = reader.selectNetwork(settings.ssid) {
                    viewModelScope.launch { stop(); mutable.update { it.copy(status = "Reader Wi-Fi lost · reconnect manually") } }
                }
                for (attempt in 0..2) {
                    ensureActive()
                    try {
                        withContext(Dispatchers.IO) {
                            reader.openSession(network, settings.host, port).use { session ->
                                mutable.update { it.copy(status = "Checking ELM interface · attempt ${attempt + 1}") }
                                val monitor = ReadOnlyMonitor(session)
                                monitor.initialize()
                                mutable.update { it.copy(status = "Live · read-only OBD", diagnostic = "${monitor.identity} · Mode 01 RPM/speed; load when supported") }
                                while (isActive) {
                                    val reading = monitor.read(::now) { Thread.sleep(80) }
                                    withContext(Dispatchers.Main) { publish(reading) }
                                    delay(500)
                                }
                            }
                        }
                        break
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        clearSession()
                        if (attempt == 2) throw error
                        mutable.update { it.copy(status = "Connection interrupted · retry ${attempt + 1}/2", diagnostic = error.message ?: "Reader error") }
                        delay((attempt + 1) * 2000L)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutable.update { it.copy(status = "Disconnected · ${error.message ?: "Reader unavailable"}", diagnostic = error.javaClass.simpleName) }
            } finally {
                reader.close()
                if (connection === reader) connection = null
                if (sessionGeneration == generation) mutable.update { it.copy(running = false, reading = null, gear = null, advice = Advice.WAIT, coachingDecision = CoachingDecision(), calibrationReadiness = emptyMap()) }
            }
        }
        watchdog = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val reading = state.value.reading
                if (reading != null && !reading.fresh(now())) {
                    estimator.reset(); coach.reset(); audio.stop(); samples.clear()
                    mutable.update { it.copy(reading = null, gear = null, advice = Advice.WAIT, coachingDecision = CoachingDecision(), calibrationReadiness = emptyMap(), status = "Live session · readings stale") }
                }
            }
        }
    }
    private fun publish(reading: Reading) {
        val s = state.value
        if ((reading.source == Source.SIMULATION) != s.simulation) return
        if (!reading.fresh(now())) {
            clearSession()
            mutable.update { it.copy(status = "Live session · readings stale") }
            return
        }
        val calibration = if (s.simulation) Simulator.calibration else Calibration(s.settings.calibration.ratios.filterKeys { it in 1..5 })
        val gear = estimator.estimate(reading, calibration, now())
        val decision = coach.evaluate(reading, gear, calibration, s.settings.coaching, now())
        val advice = decision.advice
        samples.addLast(reading)
        while (samples.size > 12) samples.removeFirst()
        val readiness = if (s.simulation) emptyMap() else (1..5).associateWith {
            CalibrationCapture.assess(samples.toList(), now(), Source.LIVE, it)
        }
        mutable.update { it.copy(reading = reading, gear = gear, advice = advice, coachingDecision = decision, calibrationReadiness = readiness) }
        audio.speak(decision, s.settings, now())
        if (s.settings.logging && now() - lastLog >= 2000) {
            lastLog = now()
            val line = "${System.currentTimeMillis()},${reading.source},${reading.rpm},${reading.kmh},${reading.load},${gear ?: "Unknown"},${advice.name},${decision.suggestedGear ?: "None"}\n"
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    if (log.length() > 1_000_000) log.writeText("")
                    log.appendText(line)
                }.onFailure { mutable.update { it.copy(note = "Could not save local lesson log") } }
            }
        }
    }
    fun captureGear(gear: Int) {
        if (state.value.simulation) { mutable.update { it.copy(note = "Simulation uses demonstration ratios. Switch to live to record a vehicle calibration.") }; return }
        if (!state.value.running) { mutable.update { it.copy(note = "Connect the reader before capturing a gear.") }; return }
        val assessment = CalibrationCapture.assess(samples.toList(), now(), Source.LIVE, gear)
        val ratio = assessment.ratio
        if (ratio == null) { mutable.update { it.copy(note = assessment.message) }; return }
        val settings = state.value.settings
        val updated = settings.calibration.ratios + (gear to ratio)
        val ordered = updated.toSortedMap().values.zipWithNext().all { (a, b) -> a > b * 1.18 }
        if (!ordered) { mutable.update { it.copy(note = "Ratio conflicts with existing calibration. Check the known gear and capture again.") }; return }
        updateSettings(settings.copy(calibration = Calibration(updated)))
        mutable.update { it.copy(note = "Gear $gear captured. At least two distinct gears are required for estimation.") }
    }
    fun review() { viewModelScope.launch(Dispatchers.IO) {
        val lines = runCatching { if (log.exists()) log.readLines().takeLast(20) else emptyList() }.getOrDefault(emptyList())
        mutable.update { it.copy(review = lines) }
    } }
    fun deleteLog() { viewModelScope.launch(Dispatchers.IO) { log.delete(); mutable.update { it.copy(review = emptyList(), note = "Local lesson log deleted") } } }
    override fun onCleared() { stop(); audio.close() }
}
