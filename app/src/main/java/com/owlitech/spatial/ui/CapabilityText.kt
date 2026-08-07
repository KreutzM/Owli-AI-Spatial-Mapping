package com.owlitech.spatial.ui

import com.owlitech.spatial.R
import com.owlitech.spatial.ar.ArCapability
import com.owlitech.spatial.ar.ArUnavailableReason
import com.owlitech.spatial.ar.InstallRequirement
import com.owlitech.spatial.ar.TransientAvailabilityReason

fun capabilityTextResource(capability: ArCapability): Int = when (capability) {
    ArCapability.Checking -> R.string.ar_checking
    ArCapability.SupportedInstalled -> R.string.ar_supported_installed
    is ArCapability.InstallRequired -> when (capability.reason) {
        InstallRequirement.ARCORE_APK_MISSING -> R.string.ar_apk_missing
        InstallRequirement.ARCORE_APK_TOO_OLD -> R.string.ar_apk_too_old
    }
    ArCapability.InstallationRequested -> R.string.ar_installation_requested
    ArCapability.Unsupported -> R.string.ar_unsupported
    is ArCapability.Transient -> when (capability.reason) {
        TransientAvailabilityReason.CHECKING -> R.string.ar_transient_checking
        TransientAvailabilityReason.TIMED_OUT -> R.string.ar_transient_timed_out
        TransientAvailabilityReason.UNKNOWN_ERROR -> R.string.ar_transient_error
    }
    is ArCapability.Unavailable -> when (capability.reason) {
        ArUnavailableReason.USER_DECLINED_INSTALLATION -> R.string.ar_user_declined
        ArUnavailableReason.DEVICE_INCOMPATIBLE -> R.string.ar_device_incompatible
        ArUnavailableReason.ARCORE_APK_MISSING -> R.string.ar_apk_missing
        ArUnavailableReason.ARCORE_APK_TOO_OLD -> R.string.ar_apk_too_old
        ArUnavailableReason.SDK_TOO_OLD -> R.string.ar_sdk_too_old
        ArUnavailableReason.UNEXPECTED_RUNTIME_ERROR -> R.string.ar_runtime_error
    }
}
