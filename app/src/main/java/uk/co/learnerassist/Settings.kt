package uk.co.learnerassist

import android.content.Context
import uk.co.learnerassist.core.*

data class Settings(
    val ssid: String = "WiFi_OBDII", val host: String = "", val port: String = "",
    val vehicle: String = "Ford Fiesta 2015 · 1.0 EcoBoost",
    val calibration: Calibration = Calibration(), val coaching: CoachingConfig = CoachingConfig(),
    val audio: Boolean = false, val volume: Float = 0.5f, val logging: Boolean = false,
)
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("learnerassist", Context.MODE_PRIVATE)
    fun load() = Settings(
        ssid = prefs.getString("ssid", "WiFi_OBDII")!!,
        host = prefs.getString("host", "")!!, port = prefs.getString("port", "")!!,
        vehicle = prefs.getString("vehicle", "Ford Fiesta 2015 · 1.0 EcoBoost")!!,
        calibration = Calibration((1..6).mapNotNull { gear ->
            prefs.getString("ratio$gear", null)?.toDoubleOrNull()?.let { gear to it }
        }.toMap()),
        coaching = CoachingConfig(
            mode = DrivingMode.entries.firstOrNull { it.name == prefs.getString("mode", "BEGINNER") } ?: DrivingMode.BEGINNER,
            upRpm = prefs.getInt("shiftMin", 2000).coerceIn(1500, 2700),
            maxShiftRpm = prefs.getInt("shiftMax", 3000).coerceIn(3000, 4500),
            profile = TeachingProfile((1..4).mapIndexed { index, gear ->
                prefs.getFloat("speedGuide$gear", listOf(10f, 25f, 35f, 50f)[index]).toDouble()
            }).let { if (it.valid()) it else TeachingProfile() },
            downRpm = prefs.getInt("down", 1250).coerceIn(900, 1600),
            intervalSeconds = prefs.getInt("promptGapSeconds", 3).coerceIn(1, 60)),
        audio = prefs.getBoolean("audio", false), volume = prefs.getFloat("volume", 0.5f).coerceIn(0f, 1f),
        logging = prefs.getBoolean("logging", false))
    fun save(settings: Settings) {
        prefs.edit().apply {
            putString("ssid", settings.ssid); putString("host", settings.host); putString("port", settings.port)
            putString("vehicle", settings.vehicle); putString("mode", settings.coaching.mode.name)
            putInt("shiftMin", settings.coaching.upRpm); putInt("shiftMax", settings.coaching.maxShiftRpm); putInt("down", settings.coaching.downRpm)
            settings.coaching.profile.boundariesMph.forEachIndexed { index, value -> putFloat("speedGuide${index + 1}", value.toFloat()) }
            putInt("promptGapSeconds", settings.coaching.intervalSeconds); putBoolean("audio", settings.audio)
            putFloat("volume", settings.volume); putBoolean("logging", settings.logging)
            (1..6).forEach { putString("ratio$it", settings.calibration.ratios[it]?.toString()) }
        }.apply()
    }
}
