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
