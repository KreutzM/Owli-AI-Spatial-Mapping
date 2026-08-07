package com.owlitech.spatial.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owlitech.spatial.R
import com.owlitech.spatial.ar.ArCapability
import com.owlitech.spatial.ar.ArDiagnosticState
import com.owlitech.spatial.ar.CameraPermissionAction
import com.owlitech.spatial.ar.CameraPermissionState
import com.owlitech.spatial.ar.DepthAcquisitionStatus
import com.owlitech.spatial.ar.DepthConfigurationStatus
import com.owlitech.spatial.ar.DepthDataKind
import com.owlitech.spatial.ar.DepthDiagnosticState
import com.owlitech.spatial.ar.DiagnosticDepthMode
import com.owlitech.spatial.ar.DiagnosticObservation
import com.owlitech.spatial.ar.DiagnosticTrackingFailureReason
import com.owlitech.spatial.ar.DiagnosticTrackingState
import com.owlitech.spatial.ar.SessionFailure
import com.owlitech.spatial.ar.SessionLifecycleState
import com.owlitech.spatial.ar.SessionOperation
import com.owlitech.spatial.ar.availableAction
import java.util.Locale

object DiagnosticTestTags {
    const val ARCORE_CARD = "diagnostic_arcore"
    const val CAMERA_PERMISSION_CARD = "diagnostic_camera_permission"
    const val SESSION_CARD = "diagnostic_session"
    const val TRACKING_CARD = "diagnostic_tracking"
    const val FRAME_CARD = "diagnostic_frame"
    const val POSE_CARD = "diagnostic_pose"
    const val INTRINSICS_CARD = "diagnostic_intrinsics"
    const val RAW_DEPTH_CARD = "diagnostic_raw_depth"
    const val MAPPING_CARD = "diagnostic_mapping"
    const val GL_SURFACE = "diagnostic_gl_surface"
    const val PERMISSION_ACTION = "diagnostic_permission_action"
}

@Composable
fun BootstrapScreen(
    state: ArDiagnosticState,
    onCheckAgain: () -> Unit,
    onInstallArCore: () -> Unit,
    onPermissionAction: (CameraPermissionAction) -> Unit,
    modifier: Modifier = Modifier,
    diagnosticSurface: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.bootstrap_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.bootstrap_subtitle),
            style = MaterialTheme.typography.bodyLarge,
        )

        StatusCard(
            label = stringResource(R.string.arcore_label),
            value = stringResource(capabilityTextResource(state.capability)),
            testTag = DiagnosticTestTags.ARCORE_CARD,
        )
        StatusCard(
            label = stringResource(R.string.camera_label),
            value = permissionText(state.cameraPermission),
            testTag = DiagnosticTestTags.CAMERA_PERMISSION_CARD,
        )
        StatusCard(
            label = stringResource(R.string.session_label),
            value = sessionText(state.sessionLifecycle),
            testTag = DiagnosticTestTags.SESSION_CARD,
        )

        diagnosticSurface()

        StatusCard(
            label = stringResource(R.string.tracking_label),
            value = trackingText(state.observation),
            explanation = trackingFailureText(state.observation),
            testTag = DiagnosticTestTags.TRACKING_CARD,
        )
        StatusCard(
            label = stringResource(R.string.frame_timestamp_label),
            value = state.observation?.frameTimestampNanos?.let { "$it ns" }
                ?: stringResource(R.string.not_available),
            explanation = stringResource(R.string.frame_timestamp_explanation),
            testTag = DiagnosticTestTags.FRAME_CARD,
        )
        PoseCard(state.observation)
        IntrinsicsCard(state.observation)
        RawDepthCard(state.depth)
        StatusCard(
            label = stringResource(R.string.mapping_label),
            value = stringResource(R.string.mapping_not_started),
            explanation = stringResource(R.string.mapping_explanation),
            testTag = DiagnosticTestTags.MAPPING_CARD,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCheckAgain,
                modifier = Modifier.semantics {
                    contentDescription = "ARCore-Verfügbarkeit erneut prüfen"
                },
            ) {
                Text(stringResource(R.string.check_again))
            }
            if (
                state.capability is ArCapability.InstallRequired ||
                state.capability == ArCapability.Unavailable(
                    com.owlitech.spatial.ar.ArUnavailableReason.USER_DECLINED_INSTALLATION,
                )
            ) {
                Button(
                    onClick = onInstallArCore,
                    modifier = Modifier.semantics {
                        contentDescription = "ARCore-Installation oder Aktualisierung starten"
                    },
                ) {
                    Text(stringResource(R.string.install_arcore))
                }
            }
            val permissionAction = state.cameraPermission.availableAction()
            if (permissionAction != CameraPermissionAction.NONE) {
                Button(
                    onClick = { onPermissionAction(permissionAction) },
                    modifier = Modifier
                        .testTag(DiagnosticTestTags.PERMISSION_ACTION)
                        .semantics {
                            contentDescription = permissionActionAccessibilityText(permissionAction)
                        },
                ) {
                    Text(permissionActionText(permissionAction))
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.privacy_notice),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.privacy_text),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PoseCard(observation: DiagnosticObservation?) {
    val pose = observation?.worldFromArCoreCamera
    val value = if (pose == null) {
        stringResource(R.string.pose_not_current)
    } else {
        val t = pose.translation
        val q = pose.rotation
        "Translation (m): x=${format(t.x)}, y=${format(t.y)}, z=${format(t.z)}\n" +
            "Quaternion (w, x, y, z): (${format(q.w)}, ${format(q.x)}, ${format(q.y)}, ${format(q.z)})"
    }
    StatusCard(
        label = stringResource(R.string.pose_label),
        value = value,
        explanation = stringResource(R.string.pose_explanation),
        testTag = DiagnosticTestTags.POSE_CARD,
    )
}

@Composable
private fun IntrinsicsCard(observation: DiagnosticObservation?) {
    val intrinsics = observation?.nativeImageIntrinsics
    val value = if (intrinsics == null) {
        stringResource(R.string.intrinsics_not_current)
    } else {
        "fx=${format(intrinsics.fx)}, fy=${format(intrinsics.fy)}, " +
            "cx=${format(intrinsics.cx)}, cy=${format(intrinsics.cy)}\n" +
            "Breite=${intrinsics.width}, Höhe=${intrinsics.height} px"
    }
    StatusCard(
        label = stringResource(R.string.intrinsics_label),
        value = value,
        explanation = stringResource(R.string.intrinsics_explanation),
        testTag = DiagnosticTestTags.INTRINSICS_CARD,
    )
}


@Composable
private fun RawDepthCard(depth: DepthDiagnosticState) {
    val configuration = depth.configuration
    val observation = depth.currentObservation
    val statistics = observation?.statistics ?: depth.lastNewDataStatistics
    val statisticsTimestamp = observation?.statistics?.let { observation.rawDepthTimestampNanos }
        ?: depth.lastNewDataTimestampNanos
    val value = buildString {
        append("RAW_DEPTH_ONLY supported: ${yesNo(configuration.rawDepthOnlySupported)}\n")
        append("AUTOMATIC supported: ${yesNo(configuration.automaticSupported)}\n")
        append("Selected mode: ${depthModeText(configuration.selectedMode)}\n")
        append("Configured mode: ${depthModeText(configuration.configuredMode)} / ${configurationStatusText(configuration.status)}\n")
        append("Acquisition: ${depthStatusText(depth.acquisitionStatus)}\n")
        append("Frame timestamp: ${observation?.frameTimestampNanos?.let { "$it ns" } ?: "—"}\n")
        append("Raw-depth timestamp: ${observation?.rawDepthTimestampNanos?.let { "$it ns" } ?: "—"}\n")
        append("Confidence timestamp: ${observation?.confidenceTimestampNanos?.let { "$it ns" } ?: "—"}\n")
        append("Sample: ${observation?.dataKind?.let(::depthKindText) ?: "—"}\n")
        if (observation != null) {
            append("Depth: ${observation.depthWidth} x ${observation.depthHeight}; rowStride=${observation.depthRowStride}, pixelStride=${observation.depthPixelStride}; format=${observation.depthFormat}\n")
            append("Confidence: ${observation.confidenceWidth} x ${observation.confidenceHeight}; rowStride=${observation.confidenceRowStride}, pixelStride=${observation.confidencePixelStride}; format=${observation.confidenceFormat}\n")
            append("Depth aspect=${format(observation.depthAspectRatio)}\n")
            observation.cpuImageIntrinsics?.let { intrinsics ->
                append("CPU intrinsics: ${intrinsics.width} x ${intrinsics.height}, aspect=${format(intrinsics.aspectRatio)}, normalized f=(${format(intrinsics.normalizedFx)}, ${format(intrinsics.normalizedFy)}), c=(${format(intrinsics.normalizedCx)}, ${format(intrinsics.normalizedCy)})\n")
            }
            observation.gpuTextureIntrinsics?.let { intrinsics ->
                append("GPU texture intrinsics: ${intrinsics.width} x ${intrinsics.height}, aspect=${format(intrinsics.aspectRatio)}\n")
            }
            append("Viewport: rotation=${observation.displayRotation}, ${observation.viewportWidth} x ${observation.viewportHeight}\n")
        }
        append("Observed NEW-depth rate: ${String.format(Locale.US, "%.2f", depth.observedNewDepthRateHz)} Hz (${depth.rateWindowSampleCount} samples in bounded window)\n")
        if (statistics != null) {
            append("Latest scanned NEW sample: ${statisticsTimestamp ?: "—"} ns\n")
            append("Non-zero depth: ${statistics.nonZeroDepthCount}/${statistics.totalPixelCount} (${percent(statistics.nonZeroDepthRatio)}); min/max=${statistics.minNonZeroDepthMillimetres ?: "—"}/${statistics.maxNonZeroDepthMillimetres ?: "—"} mm\n")
            append("Non-zero confidence: ${statistics.nonZeroConfidenceCount}/${statistics.totalPixelCount} (${percent(statistics.nonZeroConfidenceRatio)}); confidence range=${statistics.minConfidence ?: "—"}/${statistics.maxConfidence ?: "—"}\n")
            append("Confidence >= 128 (diagnostic only): ${statistics.confidenceAtLeast128Count}/${statistics.totalPixelCount} (${percent(statistics.confidenceAtLeast128Ratio)})\n")
            append("Inconsistencies: depth=0/confidence!=0 ${statistics.zeroDepthNonZeroConfidenceCount}; depth!=0/confidence=0 ${statistics.nonZeroDepthZeroConfidenceCount}\n")
        } else {
            append("Pixel statistics: no current scanned NEW sample\n")
        }
        val counters = depth.counters
        append("Counters: attempts=${counters.acquisitionAttempts}, successes=${counters.successes}, distinct NEW=${counters.distinctNewDepthTimestamps}, reprojections=${counters.reprojections}, transient unavailable=${counters.transientUnavailable}, failures=${counters.failures}")
        depth.detail?.takeIf { it.isNotBlank() }?.let { append("\nDetail: $it") }
    }
    StatusCard(
        label = stringResource(R.string.raw_depth_label),
        value = value,
        explanation = stringResource(R.string.raw_depth_explanation),
        testTag = DiagnosticTestTags.RAW_DEPTH_CARD,
    )
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun depthModeText(mode: DiagnosticDepthMode): String = when (mode) {
    DiagnosticDepthMode.NONE -> "NONE"
    DiagnosticDepthMode.RAW_DEPTH_ONLY -> "RAW_DEPTH_ONLY"
    DiagnosticDepthMode.AUTOMATIC -> "AUTOMATIC fallback"
}

private fun configurationStatusText(status: DepthConfigurationStatus): String = when (status) {
    DepthConfigurationStatus.UNSUPPORTED -> "unsupported"
    DepthConfigurationStatus.CONFIGURED -> "configured"
    DepthConfigurationStatus.CONFIGURATION_FAILED -> "configuration failed"
}

private fun depthStatusText(status: DepthAcquisitionStatus): String = when (status) {
    DepthAcquisitionStatus.UNSUPPORTED -> "Unsupported"
    DepthAcquisitionStatus.CONFIGURED_WAITING_FOR_DATA -> "Configured / WaitingForData"
    DepthAcquisitionStatus.NEW_DEPTH_DATA -> "NewDepthData"
    DepthAcquisitionStatus.REPROJECTED_DEPTH_DATA -> "ReprojectedDepthData"
    DepthAcquisitionStatus.NOT_YET_AVAILABLE -> "NotYetAvailable (transient)"
    DepthAcquisitionStatus.NOT_TRACKING -> "NotTracking"
    DepthAcquisitionStatus.ILLEGAL_STATE -> "IllegalState / mode not configured"
    DepthAcquisitionStatus.DEADLINE_EXCEEDED -> "Deadline/current-frame error"
    DepthAcquisitionStatus.RESOURCE_EXHAUSTED -> "ResourceExhausted"
    DepthAcquisitionStatus.INVALID_IMAGE_LAYOUT -> "InvalidImageLayout"
    DepthAcquisitionStatus.UNEXPECTED_RUNTIME_ERROR -> "UnexpectedRuntimeError"
}

private fun depthKindText(kind: DepthDataKind): String = when (kind) {
    DepthDataKind.NEW -> "NEW"
    DepthDataKind.REPROJECTED -> "REPROJECTED"
}

private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)

@Composable
private fun permissionText(state: CameraPermissionState): String = when (state) {
    CameraPermissionState.NOT_REQUESTED -> stringResource(R.string.camera_not_requested)
    CameraPermissionState.REQUEST_IN_FLIGHT -> stringResource(R.string.camera_request_in_flight)
    CameraPermissionState.GRANTED -> stringResource(R.string.camera_granted)
    CameraPermissionState.DENIED_CAN_ASK_AGAIN -> stringResource(R.string.camera_denied_retry)
    CameraPermissionState.REVOKED_OR_RESET_REQUESTABLE -> stringResource(
        R.string.camera_revoked_requestable,
    )
    CameraPermissionState.DENIED_PERMANENTLY -> stringResource(R.string.camera_denied_permanently)
}

@Composable
private fun permissionActionText(action: CameraPermissionAction): String = when (action) {
    CameraPermissionAction.REQUEST -> stringResource(R.string.request_camera)
    CameraPermissionAction.RETRY -> stringResource(R.string.retry_camera)
    CameraPermissionAction.OPEN_APPLICATION_SETTINGS -> stringResource(R.string.open_settings)
    CameraPermissionAction.NONE -> ""
}

private fun permissionActionAccessibilityText(action: CameraPermissionAction): String = when (action) {
    CameraPermissionAction.REQUEST -> "Kameraberechtigung anfragen"
    CameraPermissionAction.RETRY -> "Kameraberechtigung erneut anfragen"
    CameraPermissionAction.OPEN_APPLICATION_SETTINGS ->
        "Anwendungseinstellungen für Kameraberechtigung öffnen"
    CameraPermissionAction.NONE -> ""
}

@Composable
private fun sessionText(state: SessionLifecycleState): String = when (state) {
    SessionLifecycleState.WaitingForPrerequisites -> stringResource(R.string.session_waiting)
    SessionLifecycleState.WaitingForPreviousSession -> stringResource(
        R.string.session_waiting_previous,
    )
    SessionLifecycleState.Ready -> stringResource(R.string.session_ready)
    SessionLifecycleState.Creating -> stringResource(R.string.session_creating)
    SessionLifecycleState.Resuming -> stringResource(R.string.session_resuming)
    SessionLifecycleState.Running -> stringResource(R.string.session_running)
    SessionLifecycleState.Paused -> stringResource(R.string.session_paused)
    SessionLifecycleState.Closing -> stringResource(R.string.session_closing)
    SessionLifecycleState.Closed -> stringResource(R.string.session_closed)
    is SessionLifecycleState.Error -> {
        "${sessionOperationText(state.operation)}: ${sessionFailureText(state.failure)}"
    }
}

@Composable
private fun trackingText(observation: DiagnosticObservation?): String = when (
    observation?.trackingState
) {
    DiagnosticTrackingState.TRACKING -> stringResource(R.string.tracking_tracking)
    DiagnosticTrackingState.PAUSED -> stringResource(R.string.tracking_paused)
    DiagnosticTrackingState.STOPPED -> stringResource(R.string.tracking_stopped)
    null -> stringResource(R.string.tracking_no_frame)
}

@Composable
private fun trackingFailureText(observation: DiagnosticObservation?): String = when (
    observation?.trackingFailureReason
) {
    DiagnosticTrackingFailureReason.NONE -> stringResource(R.string.failure_none)
    DiagnosticTrackingFailureReason.BAD_STATE -> stringResource(R.string.failure_bad_state)
    DiagnosticTrackingFailureReason.INSUFFICIENT_LIGHT -> stringResource(
        R.string.failure_insufficient_light,
    )
    DiagnosticTrackingFailureReason.EXCESSIVE_MOTION -> stringResource(
        R.string.failure_excessive_motion,
    )
    DiagnosticTrackingFailureReason.INSUFFICIENT_FEATURES -> stringResource(
        R.string.failure_insufficient_features,
    )
    DiagnosticTrackingFailureReason.CAMERA_UNAVAILABLE -> stringResource(
        R.string.failure_camera_unavailable,
    )
    DiagnosticTrackingFailureReason.UNKNOWN -> stringResource(R.string.failure_unknown)
    null -> stringResource(R.string.failure_not_available)
}

private fun sessionOperationText(operation: SessionOperation): String = when (operation) {
    SessionOperation.CREATE -> "Erstellen"
    SessionOperation.RESUME -> "Fortsetzen"
    SessionOperation.CAMERA_TEXTURE -> "Kameratextur"
    SessionOperation.DISPLAY_GEOMETRY -> "Display-Geometrie"
    SessionOperation.UPDATE -> "Frame-Update"
    SessionOperation.PAUSE -> "Pausieren"
    SessionOperation.CLOSE -> "Schließen"
}

private fun sessionFailureText(failure: SessionFailure): String = when (failure) {
    SessionFailure.CAMERA_NOT_AVAILABLE -> "Kamera nicht verfügbar"
    SessionFailure.ARCORE_APK_MISSING -> "ARCore APK fehlt"
    SessionFailure.ARCORE_APK_TOO_OLD -> "ARCore APK ist zu alt"
    SessionFailure.SDK_TOO_OLD -> "ARCore SDK ist zu alt"
    SessionFailure.DEVICE_INCOMPATIBLE -> "Gerät inkompatibel"
    SessionFailure.MISSING_GL_CONTEXT -> "GL-Kontext fehlt"
    SessionFailure.CAMERA_TEXTURE_NOT_SET -> "Kameratextur fehlt"
    SessionFailure.SESSION_PAUSED -> "Session ist pausiert"
    SessionFailure.SESSION_NOT_PAUSED -> "Session ist nicht pausiert"
    SessionFailure.UNEXPECTED_RUNTIME_ERROR -> "Unerwarteter Laufzeitfehler"
}

private fun format(value: Double): String = String.format(Locale.US, "%.5f", value)

@Composable
private fun StatusCard(
    label: String,
    value: String,
    testTag: String,
    explanation: String? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics(mergeDescendants = true) {},
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (explanation != null) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
