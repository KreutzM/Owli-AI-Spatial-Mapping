package com.owlitech.spatial.ar

interface DiagnosticSessionPort {
    fun resume()
    fun setCameraTextureName(textureId: Int)
    fun setDisplayGeometry(displayRotation: Int, width: Int, height: Int)
    fun update(): DiagnosticFrameScalars
    fun pause()
    fun close()
}

fun interface DiagnosticSessionFactory {
    fun create(): DiagnosticSessionPort
}

class ArRuntimeException(
    val failure: SessionFailure,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface DiagnosticSurfacePort {
    fun resumeSurface()
    fun pauseSurface()
}

enum class DiagnosticSessionSlotState {
    AVAILABLE,
    OWNED,
    CLOSING,
}

/**
 * Process-wide ownership gate and asynchronous native-close boundary.
 *
 * Implementations must never invoke [DiagnosticSessionPort.close] inline. A successful slot
 * acquisition remains owned until either [releaseSessionSlot] or [scheduleClose] is called.
 * [notifyWhenAvailable] retains at most one latest waiter, preventing recreation/resume loops from
 * building an unbounded callback queue.
 */
interface DiagnosticSessionCloseScheduler {
    fun tryAcquireSessionSlot(): Boolean
    fun releaseSessionSlot()
    fun scheduleClose(
        session: DiagnosticSessionPort,
        onComplete: (Throwable?) -> Unit,
    )
    fun notifyWhenAvailable(callback: () -> Unit)
    fun currentSlotState(): DiagnosticSessionSlotState
}

interface ArInstallPort {
    fun requestInstall(userRequestedInstall: Boolean): InstallRequestResult
}

enum class InstallRequestResult {
    INSTALLED,
    INSTALL_REQUESTED,
}

class ArInstallException(
    val reason: ArUnavailableReason,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
