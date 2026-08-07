package com.owlitech.spatial.ar

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.MissingGlContextException
import com.google.ar.core.exceptions.SessionNotPausedException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.TextureNotSetException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

class ArCoreDiagnosticSessionFactory(context: Context) : DiagnosticSessionFactory {
    private val applicationContext = context.applicationContext

    override fun create(): DiagnosticSessionPort = try {
        ArCoreDiagnosticSessionPort(Session(applicationContext))
    } catch (error: Throwable) {
        throw error.asRuntimeException()
    }
}

private class ArCoreDiagnosticSessionPort(
    /** The only retained ARCore runtime object. This adapter exclusively owns it until close(). */
    private val session: Session,
) : DiagnosticSessionPort {
    override fun resume() = translateErrors { session.resume() }

    override fun setCameraTextureName(textureId: Int) = translateErrors {
        require(textureId > 0) { "Camera texture ID must be positive." }
        session.setCameraTextureName(textureId)
    }

    override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) = translateErrors {
        require(width > 0 && height > 0) { "Display dimensions must be positive." }
        session.setDisplayGeometry(displayRotation, width, height)
    }

    override fun update(): DiagnosticFrameScalars = translateErrors {
        // Frame, Camera, Pose and CameraIntrinsics are method-local and never escape this call.
        val frame = session.update()
        val camera = frame.camera
        val trackingState = camera.trackingState.toDiagnosticTrackingState()
        val trackingFailureReason = camera.trackingFailureReason.toDiagnosticTrackingFailureReason()

        if (trackingState != DiagnosticTrackingState.TRACKING) {
            return@translateErrors DiagnosticFrameScalars(
                frameTimestampNanos = frame.timestamp,
                trackingState = trackingState,
                trackingFailureReason = trackingFailureReason,
            )
        }

        val pose = camera.pose
        val translation = pose.translation
        val quaternion = pose.rotationQuaternion
        val imageIntrinsics = camera.imageIntrinsics
        val focalLength = imageIntrinsics.focalLength
        val principalPoint = imageIntrinsics.principalPoint
        val imageDimensions = imageIntrinsics.imageDimensions

        DiagnosticFrameScalars(
            frameTimestampNanos = frame.timestamp,
            trackingState = trackingState,
            trackingFailureReason = trackingFailureReason,
            translationMetresX = translation[0].toDouble(),
            translationMetresY = translation[1].toDouble(),
            translationMetresZ = translation[2].toDouble(),
            arCoreQuaternionX = quaternion[0].toDouble(),
            arCoreQuaternionY = quaternion[1].toDouble(),
            arCoreQuaternionZ = quaternion[2].toDouble(),
            arCoreQuaternionW = quaternion[3].toDouble(),
            imageFx = focalLength[0].toDouble(),
            imageFy = focalLength[1].toDouble(),
            imageCx = principalPoint[0].toDouble(),
            imageCy = principalPoint[1].toDouble(),
            imageWidth = imageDimensions[0],
            imageHeight = imageDimensions[1],
        )
    }

    override fun pause() = translateErrors { session.pause() }

    override fun close() = translateErrors { session.close() }
}

class ArCoreInstallAdapter(
    private val activity: Activity,
) : ArInstallPort {
    override fun requestInstall(userRequestedInstall: Boolean): InstallRequestResult = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
            ArCoreApk.InstallStatus.INSTALLED -> InstallRequestResult.INSTALLED
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> InstallRequestResult.INSTALL_REQUESTED
        }
    } catch (error: Throwable) {
        throw error.asInstallException()
    }
}

internal fun TrackingState.toDiagnosticTrackingState(): DiagnosticTrackingState = when (this) {
    TrackingState.TRACKING -> DiagnosticTrackingState.TRACKING
    TrackingState.PAUSED -> DiagnosticTrackingState.PAUSED
    TrackingState.STOPPED -> DiagnosticTrackingState.STOPPED
}

internal fun TrackingFailureReason.toDiagnosticTrackingFailureReason(): DiagnosticTrackingFailureReason = when (this) {
    TrackingFailureReason.NONE -> DiagnosticTrackingFailureReason.NONE
    TrackingFailureReason.BAD_STATE -> DiagnosticTrackingFailureReason.BAD_STATE
    TrackingFailureReason.INSUFFICIENT_LIGHT -> DiagnosticTrackingFailureReason.INSUFFICIENT_LIGHT
    TrackingFailureReason.EXCESSIVE_MOTION -> DiagnosticTrackingFailureReason.EXCESSIVE_MOTION
    TrackingFailureReason.INSUFFICIENT_FEATURES -> DiagnosticTrackingFailureReason.INSUFFICIENT_FEATURES
    TrackingFailureReason.CAMERA_UNAVAILABLE -> DiagnosticTrackingFailureReason.CAMERA_UNAVAILABLE
}

private inline fun <T> translateErrors(block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw error.asRuntimeException()
}

internal fun Throwable.asRuntimeException(): ArRuntimeException = when (this) {
    is ArRuntimeException -> this
    is CameraNotAvailableException -> ArRuntimeException(SessionFailure.CAMERA_NOT_AVAILABLE, message, this)
    is UnavailableArcoreNotInstalledException -> ArRuntimeException(SessionFailure.ARCORE_APK_MISSING, message, this)
    is UnavailableApkTooOldException -> ArRuntimeException(SessionFailure.ARCORE_APK_TOO_OLD, message, this)
    is UnavailableSdkTooOldException -> ArRuntimeException(SessionFailure.SDK_TOO_OLD, message, this)
    is UnavailableDeviceNotCompatibleException -> ArRuntimeException(SessionFailure.DEVICE_INCOMPATIBLE, message, this)
    is MissingGlContextException -> ArRuntimeException(SessionFailure.MISSING_GL_CONTEXT, message, this)
    is TextureNotSetException -> ArRuntimeException(SessionFailure.CAMERA_TEXTURE_NOT_SET, message, this)
    is SessionPausedException -> ArRuntimeException(SessionFailure.SESSION_PAUSED, message, this)
    is SessionNotPausedException -> ArRuntimeException(SessionFailure.SESSION_NOT_PAUSED, message, this)
    else -> ArRuntimeException(SessionFailure.UNEXPECTED_RUNTIME_ERROR, message, this)
}

internal fun Throwable.asInstallException(): ArInstallException = when (this) {
    is ArInstallException -> this
    is UnavailableUserDeclinedInstallationException -> ArInstallException(
        ArUnavailableReason.USER_DECLINED_INSTALLATION,
        message,
        this,
    )
    is UnavailableDeviceNotCompatibleException -> ArInstallException(
        ArUnavailableReason.DEVICE_INCOMPATIBLE,
        message,
        this,
    )
    is UnavailableArcoreNotInstalledException -> ArInstallException(
        ArUnavailableReason.ARCORE_APK_MISSING,
        message,
        this,
    )
    is UnavailableApkTooOldException -> ArInstallException(
        ArUnavailableReason.ARCORE_APK_TOO_OLD,
        message,
        this,
    )
    is UnavailableSdkTooOldException -> ArInstallException(
        ArUnavailableReason.SDK_TOO_OLD,
        message,
        this,
    )
    is FatalException -> ArInstallException(
        ArUnavailableReason.UNEXPECTED_RUNTIME_ERROR,
        message,
        this,
    )
    else -> ArInstallException(ArUnavailableReason.UNEXPECTED_RUNTIME_ERROR, message, this)
}
