package com.owlitech.spatial.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionTest {
    @Test
    fun launchWithoutCompletedResultIsInFlightNotPermanent() {
        val tracker = CameraPermissionTracker()
        tracker.onRequestLaunched()

        assertEquals(
            CameraPermissionState.REQUEST_IN_FLIGHT,
            tracker.currentState(
                granted = false,
                shouldShowRequestPermissionRationale = false,
            ),
        )
        assertEquals(CameraPermissionAction.NONE, CameraPermissionState.REQUEST_IN_FLIGHT.availableAction())
    }


    @Test
    fun inFlightRetrySurvivesTrackerRecreationWithoutReusingEarlierDenial() {
        val tracker = CameraPermissionTracker(
            CameraPermissionRequestRecord(
                lastCompletedOutcome = CameraPermissionRequestOutcome.DENIED,
            ),
        )
        tracker.onRequestLaunched()

        val recreated = CameraPermissionTracker(tracker.snapshot())

        assertEquals(
            CameraPermissionState.REQUEST_IN_FLIGHT,
            recreated.currentState(
                granted = false,
                shouldShowRequestPermissionRationale = false,
            ),
        )
    }

    @Test
    fun interruptedRequestCanBeRestoredAsUnknownAndRequestable() {
        val restored = CameraPermissionTracker(
            CameraPermissionRequestRecord(
                requestInFlight = false,
                lastCompletedOutcome = CameraPermissionRequestOutcome.NONE,
            ),
        )

        val state = restored.currentState(
            granted = false,
            shouldShowRequestPermissionRationale = false,
        )
        assertEquals(CameraPermissionState.NOT_REQUESTED, state)
        assertEquals(CameraPermissionAction.REQUEST, state.availableAction())
    }

    @Test
    fun onlyCompletedDeniedOutcomeCanBecomePermanent() {
        val tracker = CameraPermissionTracker()
        tracker.onRequestLaunched()
        tracker.onRequestResult(granted = false)

        assertEquals(
            CameraPermissionState.DENIED_CAN_ASK_AGAIN,
            tracker.currentState(
                granted = false,
                shouldShowRequestPermissionRationale = true,
            ),
        )
        assertEquals(
            CameraPermissionState.DENIED_PERMANENTLY,
            tracker.currentState(
                granted = false,
                shouldShowRequestPermissionRationale = false,
            ),
        )
    }

    @Test
    fun revocationOrAutoResetAfterGrantRemainsRequestable() {
        val tracker = CameraPermissionTracker()
        tracker.observeGrantedPermission()

        val state = tracker.currentState(
            granted = false,
            shouldShowRequestPermissionRationale = false,
        )
        assertEquals(CameraPermissionState.REVOKED_OR_RESET_REQUESTABLE, state)
        assertEquals(CameraPermissionAction.REQUEST, state.availableAction())
    }

    @Test
    fun completedGrantIsPersistableSeparatelyFromRequestInFlight() {
        val tracker = CameraPermissionTracker()
        tracker.onRequestLaunched()
        assertTrue(tracker.snapshot().requestInFlight)
        tracker.onRequestResult(granted = true)

        val record = tracker.snapshot()
        assertFalse(record.requestInFlight)
        assertEquals(CameraPermissionRequestOutcome.GRANTED, record.lastCompletedOutcome)
        assertEquals(
            CameraPermissionState.GRANTED,
            tracker.currentState(granted = true, shouldShowRequestPermissionRationale = false),
        )
    }

    @Test
    fun sessionPrerequisitesRequireInstalledArCoreAndGrantedCameraPermission() {
        assertTrue(
            sessionPrerequisitesSatisfied(
                ArCapability.SupportedInstalled,
                CameraPermissionState.GRANTED,
            ),
        )
        assertFalse(
            sessionPrerequisitesSatisfied(
                ArCapability.InstallRequired(InstallRequirement.ARCORE_APK_MISSING),
                CameraPermissionState.GRANTED,
            ),
        )
        assertFalse(
            sessionPrerequisitesSatisfied(
                ArCapability.SupportedInstalled,
                CameraPermissionState.REVOKED_OR_RESET_REQUESTABLE,
            ),
        )
    }
}
