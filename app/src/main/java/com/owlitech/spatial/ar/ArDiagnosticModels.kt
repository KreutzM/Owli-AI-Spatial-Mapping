package com.owlitech.spatial.ar

import com.owlitech.spatial.core.geometry.Quaterniond
import com.owlitech.spatial.core.geometry.RigidTransform
import com.owlitech.spatial.core.geometry.Vec3d

sealed interface ArCapability {
    data object Checking : ArCapability
    data object SupportedInstalled : ArCapability
    data class InstallRequired(val reason: InstallRequirement) : ArCapability
    data object InstallationRequested : ArCapability
    data object Unsupported : ArCapability
    data class Transient(val reason: TransientAvailabilityReason) : ArCapability
    data class Unavailable(val reason: ArUnavailableReason) : ArCapability
}

enum class InstallRequirement {
    ARCORE_APK_MISSING,
    ARCORE_APK_TOO_OLD,
}

enum class TransientAvailabilityReason {
    CHECKING,
    TIMED_OUT,
    UNKNOWN_ERROR,
}

enum class ArUnavailableReason {
    USER_DECLINED_INSTALLATION,
    DEVICE_INCOMPATIBLE,
    ARCORE_APK_MISSING,
    ARCORE_APK_TOO_OLD,
    SDK_TOO_OLD,
    UNEXPECTED_RUNTIME_ERROR,
}

fun interface ArCapabilityProbe {
    fun check(): ArCapability
}

enum class CameraPermissionRequestOutcome {
    NONE,
    GRANTED,
    DENIED,
}

data class CameraPermissionRequestRecord(
    val requestInFlight: Boolean = false,
    val lastCompletedOutcome: CameraPermissionRequestOutcome = CameraPermissionRequestOutcome.NONE,
)

enum class CameraPermissionState {
    NOT_REQUESTED,
    REQUEST_IN_FLIGHT,
    GRANTED,
    DENIED_CAN_ASK_AGAIN,
    REVOKED_OR_RESET_REQUESTABLE,
    DENIED_PERMANENTLY,
}

enum class CameraPermissionAction {
    REQUEST,
    RETRY,
    OPEN_APPLICATION_SETTINGS,
    NONE,
}

fun CameraPermissionState.availableAction(): CameraPermissionAction = when (this) {
    CameraPermissionState.NOT_REQUESTED -> CameraPermissionAction.REQUEST
    CameraPermissionState.REQUEST_IN_FLIGHT -> CameraPermissionAction.NONE
    CameraPermissionState.GRANTED -> CameraPermissionAction.NONE
    CameraPermissionState.DENIED_CAN_ASK_AGAIN -> CameraPermissionAction.RETRY
    CameraPermissionState.REVOKED_OR_RESET_REQUESTABLE -> CameraPermissionAction.REQUEST
    CameraPermissionState.DENIED_PERMANENTLY -> CameraPermissionAction.OPEN_APPLICATION_SETTINGS
}

fun sessionPrerequisitesSatisfied(
    capability: ArCapability,
    cameraPermission: CameraPermissionState,
): Boolean = capability == ArCapability.SupportedInstalled &&
    cameraPermission == CameraPermissionState.GRANTED

enum class SessionOperation {
    CREATE,
    RESUME,
    CAMERA_TEXTURE,
    DISPLAY_GEOMETRY,
    UPDATE,
    PAUSE,
    CLOSE,
}

enum class SessionFailure {
    CAMERA_NOT_AVAILABLE,
    ARCORE_APK_MISSING,
    ARCORE_APK_TOO_OLD,
    SDK_TOO_OLD,
    DEVICE_INCOMPATIBLE,
    MISSING_GL_CONTEXT,
    CAMERA_TEXTURE_NOT_SET,
    SESSION_PAUSED,
    SESSION_NOT_PAUSED,
    UNEXPECTED_RUNTIME_ERROR,
}

sealed interface SessionLifecycleState {
    data object WaitingForPrerequisites : SessionLifecycleState
    data object WaitingForPreviousSession : SessionLifecycleState
    data object Ready : SessionLifecycleState
    data object Creating : SessionLifecycleState
    data object Resuming : SessionLifecycleState
    data object Running : SessionLifecycleState
    data object Paused : SessionLifecycleState
    data object Closing : SessionLifecycleState
    data object Closed : SessionLifecycleState
    data class Error(
        val operation: SessionOperation,
        val failure: SessionFailure,
        val detail: String? = null,
    ) : SessionLifecycleState
}

enum class DiagnosticTrackingState {
    TRACKING,
    PAUSED,
    STOPPED,
}

enum class DiagnosticTrackingFailureReason {
    NONE,
    BAD_STATE,
    INSUFFICIENT_LIGHT,
    EXCESSIVE_MOTION,
    INSUFFICIENT_FEATURES,
    CAMERA_UNAVAILABLE,
    UNKNOWN,
}

data class NativeImageIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
) {
    init {
        require(fx.isFinite() && fx > 0.0 && fy.isFinite() && fy > 0.0) {
            "Native focal lengths must be finite and positive."
        }
        require(cx.isFinite() && cy.isFinite()) {
            "Native principal-point coordinates must be finite."
        }
        require(width > 0 && height > 0) { "Native image dimensions must be positive." }
    }

    companion object {
        const val COORDINATE_SPACE = "ARCore native CPU image coordinates"
    }
}

data class DiagnosticObservation(
    /** ARCore Frame.timestamp in nanoseconds. Its time base is not interpreted here. */
    val frameTimestampNanos: Long,
    val trackingState: DiagnosticTrackingState,
    val trackingFailureReason: DiagnosticTrackingFailureReason,
    /** Current worldFromArCoreCamera pose. Present only while TRACKING. Translation is metres. */
    val worldFromArCoreCamera: RigidTransform?,
    /** Native CPU image intrinsics. Present only while TRACKING and after scalar validation. */
    val nativeImageIntrinsics: NativeImageIntrinsics?,
)

data class ArDiagnosticState(
    val capability: ArCapability = ArCapability.Checking,
    val cameraPermission: CameraPermissionState = CameraPermissionState.NOT_REQUESTED,
    val sessionLifecycle: SessionLifecycleState = SessionLifecycleState.WaitingForPrerequisites,
    val observation: DiagnosticObservation? = null,
    val depth: DepthDiagnosticState = DepthDiagnosticState(),
)

data class DiagnosticFrameScalars(
    val frameTimestampNanos: Long,
    val trackingState: DiagnosticTrackingState,
    val trackingFailureReason: DiagnosticTrackingFailureReason,
    val translationMetresX: Double? = null,
    val translationMetresY: Double? = null,
    val translationMetresZ: Double? = null,
    val arCoreQuaternionX: Double? = null,
    val arCoreQuaternionY: Double? = null,
    val arCoreQuaternionZ: Double? = null,
    val arCoreQuaternionW: Double? = null,
    val imageFx: Double? = null,
    val imageFy: Double? = null,
    val imageCx: Double? = null,
    val imageCy: Double? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val depthDiagnostic: DepthDiagnosticState? = null,
)

internal fun DiagnosticFrameScalars.toWorldFromCameraOrNull(): RigidTransform? {
    if (trackingState != DiagnosticTrackingState.TRACKING) return null
    val values = listOf(
        translationMetresX,
        translationMetresY,
        translationMetresZ,
        arCoreQuaternionX,
        arCoreQuaternionY,
        arCoreQuaternionZ,
        arCoreQuaternionW,
    )
    if (values.any { it == null || !it.isFinite() }) return null
    return RigidTransform(
        translation = Vec3d(
            x = translationMetresX!!,
            y = translationMetresY!!,
            z = translationMetresZ!!,
        ),
        rotation = Quaterniond(
            w = arCoreQuaternionW!!,
            x = arCoreQuaternionX!!,
            y = arCoreQuaternionY!!,
            z = arCoreQuaternionZ!!,
        ),
    )
}
