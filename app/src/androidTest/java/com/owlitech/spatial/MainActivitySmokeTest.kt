package com.owlitech.spatial

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.owlitech.spatial.ui.DiagnosticTestTags
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val activityRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndRelaunchesWithoutLifecycleCrash() {
        activityRule.onNodeWithText("ARCore-Diagnose").assertExists()
        activityRule.onNodeWithTag(DiagnosticTestTags.ARCORE_CARD).assertExists()
        activityRule.onNodeWithTag(DiagnosticTestTags.SESSION_CARD).assertExists()
        val understandableAvailabilityTexts = listOf(
            "Unterstützt und installiert",
            "Unterstützt; Google Play-Dienste für AR fehlen",
            "Unterstützt; Google Play-Dienste für AR müssen aktualisiert werden",
            "Auf diesem Gerät nicht unterstützt",
            "Verfügbarkeit wird noch geprüft",
            "Verfügbarkeitsprüfung hat das Zeitlimit erreicht",
            "Verfügbarkeit ist vorübergehend unbekannt",
            "Gerät ist mit ARCore inkompatibel",
            "Unerwarteter ARCore-Laufzeitfehler",
        )
        activityRule.waitUntil(timeoutMillis = 8_000) {
            understandableAvailabilityTexts.any { text ->
                activityRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }

        activityRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        activityRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        activityRule.activityRule.scenario.recreate()

        activityRule.onNodeWithText("ARCore-Diagnose").assertExists()
        activityRule.onNodeWithTag(DiagnosticTestTags.RAW_DEPTH_CARD).performScrollTo().assertExists()
        activityRule.onNodeWithTag(DiagnosticTestTags.MAPPING_CARD).performScrollTo().assertExists()
    }
}
