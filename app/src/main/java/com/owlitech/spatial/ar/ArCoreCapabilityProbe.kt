package com.owlitech.spatial.ar

import android.content.Context
import com.google.ar.core.ArCoreApk

class ArCoreCapabilityProbe(context: Context) : ArCapabilityProbe {
    private val applicationContext = context.applicationContext

    override fun check(): ArCapability = try {
        mapArCoreAvailability(ArCoreApk.getInstance().checkAvailability(applicationContext))
    } catch (_: RuntimeException) {
        ArCapability.Unavailable(ArUnavailableReason.UNEXPECTED_RUNTIME_ERROR)
    }
}

internal fun mapArCoreAvailability(availability: ArCoreApk.Availability): ArCapability = when (availability) {
    ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCapability.SupportedInstalled
    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArCapability.InstallRequired(
        InstallRequirement.ARCORE_APK_MISSING,
    )
    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArCapability.InstallRequired(
        InstallRequirement.ARCORE_APK_TOO_OLD,
    )
    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArCapability.Unsupported
    ArCoreApk.Availability.UNKNOWN_CHECKING -> ArCapability.Transient(
        TransientAvailabilityReason.CHECKING,
    )
    ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> ArCapability.Transient(
        TransientAvailabilityReason.TIMED_OUT,
    )
    ArCoreApk.Availability.UNKNOWN_ERROR -> ArCapability.Transient(
        TransientAvailabilityReason.UNKNOWN_ERROR,
    )
}
