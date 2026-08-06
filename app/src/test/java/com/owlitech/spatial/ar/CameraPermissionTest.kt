package com.owlitech.spatial.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionTest {
    @Test
    fun permissionStateAndAvailableActionMatchRuntimeFacts() {
        assertState(false, false, false, CameraPermissionState.NOT_REQUESTED, CameraPermissionAction.REQUEST)
        assertState(true, false, false, CameraPermissionState.GRANTED, CameraPermissionAction.NONE)
        assertState(false, true, true, CameraPermissionState.DENIED_CAN_ASK_AGAIN, CameraPermissionAction.RETRY)
        assertState(false, true, false, CameraPermissionState.DENIED_PERMANENTLY, CameraPermissionAction.OPEN_APPLICATION_SETTINGS)
    }

    private fun assertState(
        granted: Boolean,
        requestWasMade: Boolean,
        rationale: Boolean,
        expectedState: CameraPermissionState,
        expectedAction: CameraPermissionAction,
    ) {
        val state = cameraPermissionState(granted, requestWasMade, rationale)
        assertEquals(expectedState, state)
        assertEquals(expectedAction, state.availableAction())
    }
    @Test
    fun sessionPrerequisitesRequireBothInstalledArCoreAndGrantedCameraPermission() {
        assertEquals(
            true,
            sessionPrerequisitesSatisfied(
                ArCapability.SupportedInstalled,
                CameraPermissionState.GRANTED,
            ),
        )
        assertEquals(
            false,
            sessionPrerequisitesSatisfied(
                ArCapability.InstallRequired(InstallRequirement.ARCORE_APK_MISSING),
                CameraPermissionState.GRANTED,
            ),
        )
        assertEquals(
            false,
            sessionPrerequisitesSatisfied(
                ArCapability.SupportedInstalled,
                CameraPermissionState.DENIED_CAN_ASK_AGAIN,
            ),
        )
    }

}
