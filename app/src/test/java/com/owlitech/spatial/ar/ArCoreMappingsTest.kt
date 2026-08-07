package com.owlitech.spatial.ar

import com.google.ar.core.ArCoreApk
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.MissingGlContextException
import com.google.ar.core.exceptions.SessionNotPausedException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.TextureNotSetException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreMappingsTest {
    @Test
    fun everyAvailabilityMapsToRepositoryOwnedCapability() {
        val expected = mapOf(
            ArCoreApk.Availability.SUPPORTED_INSTALLED to ArCapability.SupportedInstalled,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED to ArCapability.InstallRequired(
                InstallRequirement.ARCORE_APK_MISSING,
            ),
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD to ArCapability.InstallRequired(
                InstallRequirement.ARCORE_APK_TOO_OLD,
            ),
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE to ArCapability.Unsupported,
            ArCoreApk.Availability.UNKNOWN_CHECKING to ArCapability.Transient(
                TransientAvailabilityReason.CHECKING,
            ),
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT to ArCapability.Transient(
                TransientAvailabilityReason.TIMED_OUT,
            ),
            ArCoreApk.Availability.UNKNOWN_ERROR to ArCapability.Transient(
                TransientAvailabilityReason.UNKNOWN_ERROR,
            ),
        )

        ArCoreApk.Availability.values().forEach { availability ->
            assertEquals(expected.getValue(availability), mapArCoreAvailability(availability))
        }
    }

    @Test
    fun trackingStatesAndFailureReasonsMapWithoutArCoreTypesEscaping() {
        assertEquals(DiagnosticTrackingState.TRACKING, TrackingState.TRACKING.toDiagnosticTrackingState())
        assertEquals(DiagnosticTrackingState.PAUSED, TrackingState.PAUSED.toDiagnosticTrackingState())
        assertEquals(DiagnosticTrackingState.STOPPED, TrackingState.STOPPED.toDiagnosticTrackingState())

        val expectedFailures = mapOf(
            TrackingFailureReason.NONE to DiagnosticTrackingFailureReason.NONE,
            TrackingFailureReason.BAD_STATE to DiagnosticTrackingFailureReason.BAD_STATE,
            TrackingFailureReason.INSUFFICIENT_LIGHT to DiagnosticTrackingFailureReason.INSUFFICIENT_LIGHT,
            TrackingFailureReason.EXCESSIVE_MOTION to DiagnosticTrackingFailureReason.EXCESSIVE_MOTION,
            TrackingFailureReason.INSUFFICIENT_FEATURES to DiagnosticTrackingFailureReason.INSUFFICIENT_FEATURES,
            TrackingFailureReason.CAMERA_UNAVAILABLE to DiagnosticTrackingFailureReason.CAMERA_UNAVAILABLE,
        )
        TrackingFailureReason.values().forEach { reason ->
            assertEquals(expectedFailures.getValue(reason), reason.toDiagnosticTrackingFailureReason())
        }
    }

    @Test
    fun knownSessionExceptionsMapToPreciseFailures() {
        val expected = listOf(
            CameraNotAvailableException() to SessionFailure.CAMERA_NOT_AVAILABLE,
            UnavailableArcoreNotInstalledException() to SessionFailure.ARCORE_APK_MISSING,
            UnavailableApkTooOldException() to SessionFailure.ARCORE_APK_TOO_OLD,
            UnavailableSdkTooOldException() to SessionFailure.SDK_TOO_OLD,
            UnavailableDeviceNotCompatibleException() to SessionFailure.DEVICE_INCOMPATIBLE,
            MissingGlContextException() to SessionFailure.MISSING_GL_CONTEXT,
            TextureNotSetException() to SessionFailure.CAMERA_TEXTURE_NOT_SET,
            SessionPausedException() to SessionFailure.SESSION_PAUSED,
            SessionNotPausedException() to SessionFailure.SESSION_NOT_PAUSED,
            IllegalStateException("unexpected") to SessionFailure.UNEXPECTED_RUNTIME_ERROR,
        )

        expected.forEach { (error, failure) ->
            assertEquals(failure, error.asRuntimeException().failure)
        }
    }

    @Test
    fun knownInstallExceptionsMapToPreciseUnavailableReasons() {
        val expected = listOf(
            UnavailableUserDeclinedInstallationException() to ArUnavailableReason.USER_DECLINED_INSTALLATION,
            UnavailableDeviceNotCompatibleException() to ArUnavailableReason.DEVICE_INCOMPATIBLE,
            UnavailableArcoreNotInstalledException() to ArUnavailableReason.ARCORE_APK_MISSING,
            UnavailableApkTooOldException() to ArUnavailableReason.ARCORE_APK_TOO_OLD,
            UnavailableSdkTooOldException() to ArUnavailableReason.SDK_TOO_OLD,
            IllegalStateException("unexpected") to ArUnavailableReason.UNEXPECTED_RUNTIME_ERROR,
        )

        expected.forEach { (error, reason) ->
            assertEquals(reason, error.asInstallException().reason)
        }
    }
}
