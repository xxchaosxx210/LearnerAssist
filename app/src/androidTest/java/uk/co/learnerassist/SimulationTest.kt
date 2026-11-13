package uk.co.learnerassist

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No hardware activation: all tests run the app's default simulator. */
class SimulationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
        compose.onNodeWithText("Unknown").assertExists()
    }
    @Test fun settingsPersistAndAudioDefaultsOff() {
        val store = SettingsStore(compose.activity)
        val original = store.load()
        try {
            store.save(original.copy(vehicle = "Test profile", host = "192.0.2.1", port = "1234", audio = false))
            val reloaded = SettingsStore(compose.activity).load()
            assertEquals("Test profile", reloaded.vehicle)
            assertEquals("1234", reloaded.port)
            assertFalse(reloaded.audio)
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
        compose.onNodeWithText("2").assertExists()
    }
}
