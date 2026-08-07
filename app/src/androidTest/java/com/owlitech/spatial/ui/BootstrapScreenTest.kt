package com.owlitech.spatial.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.owlitech.spatial.ar.ArCapability
import com.owlitech.spatial.ar.ArDiagnosticState
import com.owlitech.spatial.ar.CameraPermissionState
import com.owlitech.spatial.ar.InstallRequirement
import com.owlitech.spatial.ar.SessionLifecycleState
import org.junit.Rule
import org.junit.Test

class BootstrapScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unsupportedDeviceStillShowsUnderstandableDiagnosticState() {
        composeRule.setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    state = ArDiagnosticState(
                        capability = ArCapability.Unsupported,
                        cameraPermission = CameraPermissionState.NOT_REQUESTED,
                        sessionLifecycle = SessionLifecycleState.WaitingForPrerequisites,
                    ),
                    onCheckAgain = {},
                    onInstallArCore = {},
                    onPermissionAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Auf diesem Gerät nicht unterstützt").assertIsDisplayed()
        composeRule.onNodeWithTag(DiagnosticTestTags.MAPPING_CARD).performScrollTo()
        composeRule.onNodeWithText("Mapping wurde nicht gestartet").assertIsDisplayed()
        composeRule.onNodeWithTag(DiagnosticTestTags.PERMISSION_ACTION).assertExists()
    }

    @Test
    fun allDiagnosticCardsExposeSemantics() {
        composeRule.setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    state = ArDiagnosticState(
                        capability = ArCapability.Unsupported,
                        cameraPermission = CameraPermissionState.DENIED_CAN_ASK_AGAIN,
                        sessionLifecycle = SessionLifecycleState.Paused,
                    ),
                    onCheckAgain = {},
                    onInstallArCore = {},
                    onPermissionAction = {},
                )
            }
        }

        listOf(
            DiagnosticTestTags.ARCORE_CARD,
            DiagnosticTestTags.CAMERA_PERMISSION_CARD,
            DiagnosticTestTags.SESSION_CARD,
            DiagnosticTestTags.TRACKING_CARD,
            DiagnosticTestTags.FRAME_CARD,
            DiagnosticTestTags.POSE_CARD,
            DiagnosticTestTags.INTRINSICS_CARD,
            DiagnosticTestTags.MAPPING_CARD,
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertExists()
        }
    }

    @Test
    fun availableActionsHaveUnderstandableAccessibilityText() {
        composeRule.setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    state = ArDiagnosticState(
                        capability = ArCapability.InstallRequired(
                            InstallRequirement.ARCORE_APK_MISSING,
                        ),
                        cameraPermission = CameraPermissionState.NOT_REQUESTED,
                        sessionLifecycle = SessionLifecycleState.WaitingForPrerequisites,
                    ),
                    onCheckAgain = {},
                    onInstallArCore = {},
                    onPermissionAction = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "ARCore-Verfügbarkeit erneut prüfen",
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            "ARCore-Installation oder Aktualisierung starten",
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            "Kameraberechtigung anfragen",
        ).assertExists()
    }

    @Test
    fun inFlightPermissionDoesNotOfferAnotherRuntimeDialog() {
        composeRule.setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    state = ArDiagnosticState(
                        capability = ArCapability.Unsupported,
                        cameraPermission = CameraPermissionState.REQUEST_IN_FLIGHT,
                        sessionLifecycle = SessionLifecycleState.WaitingForPrerequisites,
                    ),
                    onCheckAgain = {},
                    onInstallArCore = {},
                    onPermissionAction = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "Berechtigungsanfrage läuft; Ergebnis steht noch aus",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(DiagnosticTestTags.PERMISSION_ACTION).assertDoesNotExist()
    }

    @Test
    fun revokedPermissionIsExplicitlyRequestableInsteadOfPermanent() {
        composeRule.setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    state = ArDiagnosticState(
                        capability = ArCapability.Unsupported,
                        cameraPermission = CameraPermissionState.REVOKED_OR_RESET_REQUESTABLE,
                        sessionLifecycle = SessionLifecycleState.WaitingForPrerequisites,
                    ),
                    onCheckAgain = {},
                    onInstallArCore = {},
                    onPermissionAction = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "Frühere Erteilung wurde entzogen oder zurückgesetzt; erneute Anfrage möglich",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Kameraberechtigung anfragen").assertExists()
    }

    @Test
    fun closingStateIsVisibleWithoutClaimingSessionAlreadyClosed() {
        composeRule.setContent {
            OwliSpatialTheme {
                BootstrapScreen(
                    state = ArDiagnosticState(
                        capability = ArCapability.SupportedInstalled,
                        cameraPermission = CameraPermissionState.GRANTED,
                        sessionLifecycle = SessionLifecycleState.Closing,
                    ),
                    onCheckAgain = {},
                    onInstallArCore = {},
                    onPermissionAction = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "Session-Verwendung beendet; native Freigabe läuft im Hintergrund",
        ).assertIsDisplayed()
    }
}
