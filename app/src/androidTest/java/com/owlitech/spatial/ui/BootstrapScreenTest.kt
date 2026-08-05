package com.owlitech.spatial.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.owlitech.spatial.ar.ArCapability
import org.junit.Rule
import org.junit.Test

class BootstrapScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unsupportedDeviceStillShowsSafeBootstrapState() {
        composeRule.setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    capability = ArCapability.Unsupported,
                    cameraPermissionGranted = false,
                    onCheckAgain = {},
                    onRequestCamera = {},
                )
            }
        }

        composeRule.onNodeWithText("Auf diesem Gerät nicht unterstützt").assertIsDisplayed()
        composeRule.onNodeWithText("Noch nicht implementiert").assertIsDisplayed()
        composeRule.onNodeWithText("Kamerazugriff anfordern").assertIsDisplayed()
    }
}
