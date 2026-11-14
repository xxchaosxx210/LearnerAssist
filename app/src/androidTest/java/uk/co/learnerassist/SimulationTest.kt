package uk.co.learnerassist

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import uk.co.learnerassist.core.*

/** No hardware activation: all tests run the app's default simulator. */
class SimulationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var savedSettings: Settings? = null
    @Before fun useSilentDefaultCoaching() {
        compose.waitForIdle()
        val activity = compose.activity
        compose.runOnIdle {
            savedSettings = SettingsStore(activity).load()
            ViewModelProvider(activity)[DashboardViewModel::class.java].updateSettings(savedSettings!!.copy(audio = false, coaching = CoachingConfig()))
        }
    }
    @After fun restorePersonalSettings() {
        savedSettings?.let { original ->
            compose.runOnIdle {
                ViewModelProvider(compose.activity)[DashboardViewModel::class.java].background()
                SettingsStore(compose.activity).save(original)
            }
            check(compose.activity.getSharedPreferences("learnerassist", android.content.Context.MODE_PRIVATE).edit().commit())
        }
    }
    @Test fun launchAndPauseSimulation() {
        compose.onNodeWithText("SIMULATED DATA").assertIsDisplayed()
        compose.onNodeWithText("Simulation · no vehicle connection").assertExists()
        compose.waitUntil(timeoutMillis = 6000) { compose.onAllNodesWithText("3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2559").assertExists()
        compose.onNodeWithText("30 mph").assertExists()
        compose.onNodeWithText("Pause demo").performScrollTo().performClick()
        compose.onNodeWithText("Simulation paused").assertExists()
        compose.onNodeWithText("Resume demo").performClick()
        compose.onNodeWithText("Simulation · no vehicle connection").assertExists()
    }
    @Test fun scenariosAndManualControlsAreAvailable() {
        compose.onNodeWithText("Simulator", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Clutch disengaged").performScrollTo().performClick()
        compose.onNodeWithText("✓ Clutch disengaged").assertExists()
        compose.onNodeWithText("Drive", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("estimated-gear").assertTextEquals("—")
        compose.onNodeWithTag("suggested-gear").assertTextEquals("—")
    }
    @Test fun settingsPersistAndAudioDefaultsOff() {
        val store = SettingsStore(compose.activity)
        val original = store.load()
        try {
            store.save(original.copy(vehicle = "Test profile", host = "192.0.2.1", port = "1234", audio = false,
                coaching = original.coaching.copy(profile = TeachingProfile(listOf(9.0, 24.0, 34.0, 49.0)), upRpm = 2100, maxShiftRpm = 3200)))
            val reloaded = SettingsStore(compose.activity).load()
            assertEquals("Test profile", reloaded.vehicle)
            assertEquals("1234", reloaded.port)
            assertFalse(reloaded.audio)
            assertEquals(listOf(9.0, 24.0, 34.0, 49.0), reloaded.coaching.profile.boundariesMph)
            assertEquals(2100, reloaded.coaching.upRpm); assertEquals(3200, reloaded.coaching.maxShiftRpm)
            assertEquals(original.calibration, reloaded.calibration)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("SIMULATED DATA").assertIsDisplayed()
        } finally { store.save(original) }
    }
    @Test fun liveAccessRequiresDeliberateActivationAndDoesNotResumeInBackground() {
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[DashboardViewModel::class.java]
            model.setSimulation(false)
            model.connectLive(false)
            assertFalse(model.state.value.running)
            assertNull(model.state.value.reading)
            model.background()
            model.foreground()
            assertFalse(model.state.value.running)
            assertNull(model.state.value.reading)
            model.setSimulation(true)
        }
        compose.onNodeWithText("SIMULATED DATA").assertExists()
    }
    @Test fun secondToFirstScenarioShowsConditionalPrompt() {
        compose.onNodeWithText("Simulator", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Second to first · slow progress").performScrollTo().performClick()
        compose.onNodeWithText("Drive", useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 20000) {
            compose.onAllNodesWithText("Consider first gear if continuing").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("SIMULATED DATA").assertExists()
        compose.onNodeWithTag("estimated-gear").assertTextEquals("2")
        compose.onNodeWithTag("suggested-gear").assertTextEquals("1")
    }
    @Test fun calibrationExplainsLowerFirstGearFloorAndBlocksSimulatedCapture() {
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Instructor/passenger operation only.", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("First gear can be captured from 5 mph", substring = true).assertExists()
        compose.onNodeWithText("Simulation is on — live calibration is unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Capture gear 1 ·", substring = true).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Capture gear 6 ·", substring = true).assertDoesNotExist()
    }
    @Test fun specificThirdGearMatchesTheFocusedDashboardAndIndependentSuppressesIt() {
        compose.onNodeWithText("Simulator", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Consider third gear").performScrollTo().performClick()
        compose.onNodeWithText("Drive", useUnmergedTree = true).performClick()
        compose.waitUntil(12000) { compose.onAllNodesWithText("Consider third gear").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("suggested-gear").assertTextEquals("3")
        compose.onNodeWithTag("estimated-gear").assertTextEquals("2")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(compose.activity.cacheDir, "suggestion-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNodeWithText("Independent").performScrollTo().performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("Independent driving").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("suggested-gear").assertTextEquals("—")
        compose.onNodeWithText("Independent driving").assertExists()
        compose.onNodeWithText("Routine voice and gear suggestions are off in Independent mode.").assertExists()
    }
    @Test fun highLoadScenarioSuppressesSpecificUpshift() {
        compose.onNodeWithText("Simulator", useUnmergedTree = true).performClick()
        compose.onNodeWithText("High load · suggestion paused").performScrollTo().performClick()
        compose.onNodeWithText("Drive", useUnmergedTree = true).performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithText("Suggestion paused").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("suggested-gear").assertTextEquals("—")
        compose.onNodeWithTag("estimated-gear").assertTextEquals("2")
    }
}
