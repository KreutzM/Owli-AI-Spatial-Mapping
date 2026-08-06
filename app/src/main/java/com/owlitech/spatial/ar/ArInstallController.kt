package com.owlitech.spatial.ar

class ArInstallController(
    private val installPort: ArInstallPort,
) {
    private var awaitingReturnFromInstallUi = false

    @Synchronized
    fun requestFromUser(): ArCapability {
        if (awaitingReturnFromInstallUi) return ArCapability.InstallationRequested
        return request(userRequestedInstall = true)
    }

    /**
     * Completes one pending install attempt after the Activity returns. This is deliberately called
     * at most once for a user-initiated attempt, so Activity resume cannot create an install loop.
     */
    @Synchronized
    fun onActivityResumed(): ArCapability? {
        if (!awaitingReturnFromInstallUi) return null
        awaitingReturnFromInstallUi = false
        return request(userRequestedInstall = false)
    }

    @Synchronized
    fun hasPendingInstallAttempt(): Boolean = awaitingReturnFromInstallUi

    private fun request(userRequestedInstall: Boolean): ArCapability = try {
        when (installPort.requestInstall(userRequestedInstall)) {
            InstallRequestResult.INSTALLED -> ArCapability.SupportedInstalled
            InstallRequestResult.INSTALL_REQUESTED -> {
                awaitingReturnFromInstallUi = userRequestedInstall
                ArCapability.InstallationRequested
            }
        }
    } catch (error: ArInstallException) {
        ArCapability.Unavailable(error.reason)
    } catch (_: RuntimeException) {
        ArCapability.Unavailable(ArUnavailableReason.UNEXPECTED_RUNTIME_ERROR)
    }
}
