package com.owlitech.spatial.ar

import android.app.Activity
import android.content.Context
import android.media.Image
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.MissingGlContextException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
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
        val session = Session(applicationContext)
        ArCoreDiagnosticSessionPort(
            session = session,
            depthConfiguration = configureDepthDiagnostics(session),
        )
    } catch (error: Throwable) {
        throw error.asRuntimeException()
    }
}

private fun configureDepthDiagnostics(session: Session): DepthConfigurationDiagnostic {
    var rawSupported = false
    var automaticSupported = false
    return try {
        rawSupported = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        automaticSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val selected = selectDiagnosticDepthMode(rawSupported, automaticSupported)
        if (selected == DiagnosticDepthMode.NONE) {
            DepthConfigurationDiagnostic(
                rawDepthOnlySupported = rawSupported,
                automaticSupported = automaticSupported,
                selectedMode = DiagnosticDepthMode.NONE,
                status = DepthConfigurationStatus.UNSUPPORTED,
            )
        } else {
            val config = Config(session)
            config.depthMode = when (selected) {
                DiagnosticDepthMode.RAW_DEPTH_ONLY -> Config.DepthMode.RAW_DEPTH_ONLY
                DiagnosticDepthMode.AUTOMATIC -> Config.DepthMode.AUTOMATIC
                DiagnosticDepthMode.NONE -> Config.DepthMode.DISABLED
            }
            session.configure(config)
            DepthConfigurationDiagnostic(
                rawDepthOnlySupported = rawSupported,
                automaticSupported = automaticSupported,
                selectedMode = selected,
                configuredMode = selected,
                status = DepthConfigurationStatus.CONFIGURED,
            )
        }
    } catch (error: RuntimeException) {
        DepthConfigurationDiagnostic(
            rawDepthOnlySupported = rawSupported,
            automaticSupported = automaticSupported,
            selectedMode = selectDiagnosticDepthMode(rawSupported, automaticSupported),
            status = DepthConfigurationStatus.CONFIGURATION_FAILED,
            detail = error.message?.take(160),
        )
    }
}

private class ArCoreDiagnosticSessionPort(
    /** The only retained ARCore runtime object. This adapter exclusively owns it until close(). */
    private val session: Session,
    override val depthConfiguration: DepthConfigurationDiagnostic,
) : DiagnosticSessionPort {
    private val depthTracker = RawDepthDiagnosticTracker(depthConfiguration)
    private var displayRotation = 0
    private var viewportWidth = 0
    private var viewportHeight = 0

    override fun resume() = translateErrors { session.resume() }

    override fun setCameraTextureName(textureId: Int) = translateErrors {
        require(textureId > 0) { "Camera texture ID must be positive." }
        session.setCameraTextureName(textureId)
    }

    override fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int) = translateErrors {
        require(width > 0 && height > 0) { "Display dimensions must be positive." }
        session.setDisplayGeometry(displayRotation, width, height)
        this.displayRotation = displayRotation
        viewportWidth = width
        viewportHeight = height
    }

    override fun update(): DiagnosticFrameScalars = translateErrors {
        // Frame/Camera/Pose/Intrinsics/Image/Plane/ByteBuffer remain method-local to this call.
        val frame = session.update()
        val camera = frame.camera
        val trackingState = camera.trackingState.toDiagnosticTrackingState()
        val trackingFailureReason = camera.trackingFailureReason.toDiagnosticTrackingFailureReason()

        if (trackingState != DiagnosticTrackingState.TRACKING) {
            return@translateErrors DiagnosticFrameScalars(
                frameTimestampNanos = frame.timestamp,
                trackingState = trackingState,
                trackingFailureReason = trackingFailureReason,
                depthDiagnostic = depthTracker.observe(
                    context = DepthFrameContext(
                        frameTimestampNanos = frame.timestamp,
                        tracking = false,
                        cpuImageIntrinsics = null,
                        gpuTextureIntrinsics = null,
                        displayRotation = displayRotation,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                    ),
                    source = null,
                ),
            )
        }

        val pose = camera.pose
        val translation = pose.translation
        val quaternion = pose.rotationQuaternion
        val imageIntrinsics = camera.imageIntrinsics.toDiagnosticIntrinsics()
        val textureIntrinsics = camera.textureIntrinsics.toDiagnosticIntrinsics()
        val depthDiagnostic = depthTracker.observe(
            context = DepthFrameContext(
                frameTimestampNanos = frame.timestamp,
                tracking = true,
                cpuImageIntrinsics = imageIntrinsics,
                gpuTextureIntrinsics = textureIntrinsics,
                displayRotation = displayRotation,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            ),
            source = if (depthConfiguration.status == DepthConfigurationStatus.CONFIGURED) {
                ArCoreRawDepthFrameSource(frame)
            } else {
                null
            },
        )

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
            imageFx = imageIntrinsics.fx,
            imageFy = imageIntrinsics.fy,
            imageCx = imageIntrinsics.cx,
            imageCy = imageIntrinsics.cy,
            imageWidth = imageIntrinsics.width,
            imageHeight = imageIntrinsics.height,
            depthDiagnostic = depthDiagnostic,
        )
    }

    override fun pause() = translateErrors { session.pause() }

    override fun close() = translateErrors { session.close() }
}

private fun com.google.ar.core.CameraIntrinsics.toDiagnosticIntrinsics(): DiagnosticCameraIntrinsics {
    val focal = focalLength
    val principal = principalPoint
    val dimensions = imageDimensions
    return DiagnosticCameraIntrinsics(
        fx = focal[0].toDouble(),
        fy = focal[1].toDouble(),
        cx = principal[0].toDouble(),
        cy = principal[1].toDouble(),
        width = dimensions[0],
        height = dimensions[1],
    )
}

private class ArCoreRawDepthFrameSource(
    private val frame: Frame,
) : RawDepthFrameSource {
    override fun acquireRawDepth16Bits(): DiagnosticDepthImage =
        acquireDepthImage { frame.acquireRawDepthImage16Bits() }

    override fun acquireRawDepthConfidence(): DiagnosticDepthImage =
        acquireDepthImage { frame.acquireRawDepthConfidenceImage() }

    private inline fun acquireDepthImage(block: () -> Image): DiagnosticDepthImage = try {
        AndroidDiagnosticDepthImage(block())
    } catch (error: Throwable) {
        throw error.asDepthAcquisitionException()
    }
}

private class AndroidDiagnosticDepthImage(
    private val image: Image,
) : DiagnosticDepthImage {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override val timestampNanos: Long get() = image.timestamp
    override val format: Int get() = image.format

    override fun plane(): DiagnosticDepthPlane = try {
        val planes = image.planes
        if (planes.size != 1) {
            throw DepthAcquisitionException(
                DepthFailureReason.INVALID_IMAGE_LAYOUT,
                "Expected exactly one image plane, got ${planes.size}.",
            )
        }
        val plane = planes[0]
        ByteBufferDepthPlane(
            buffer = plane.buffer,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        )
    } catch (error: DepthAcquisitionException) {
        throw error
    } catch (error: RuntimeException) {
        throw DepthAcquisitionException(
            DepthFailureReason.INVALID_IMAGE_LAYOUT,
            error.message,
            error,
        )
    }

    override fun close() = image.close()
}

private fun Throwable.asDepthAcquisitionException(): DepthAcquisitionException = when (this) {
    is DepthAcquisitionException -> this
    is NotYetAvailableException -> DepthAcquisitionException(DepthFailureReason.NOT_YET_AVAILABLE, message, this)
    is NotTrackingException -> DepthAcquisitionException(DepthFailureReason.NOT_TRACKING, message, this)
    is IllegalStateException -> DepthAcquisitionException(DepthFailureReason.ILLEGAL_STATE, message, this)
    is DeadlineExceededException -> DepthAcquisitionException(DepthFailureReason.DEADLINE_EXCEEDED, message, this)
    is ResourceExhaustedException -> DepthAcquisitionException(DepthFailureReason.RESOURCE_EXHAUSTED, message, this)
    is IndexOutOfBoundsException,
    is UnsupportedOperationException,
    -> DepthAcquisitionException(DepthFailureReason.INVALID_IMAGE_LAYOUT, message, this)
    else -> throw this
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
