package com.owlitech.spatial.ui

import com.owlitech.spatial.R
import com.owlitech.spatial.ar.ArCapability
import com.owlitech.spatial.ar.ArUnavailableReason
import com.owlitech.spatial.ar.InstallRequirement
import com.owlitech.spatial.ar.TransientAvailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityTextTest {
    @Test
    fun allPublicStatesHaveStableTextResources() {
        assertEquals(R.string.ar_checking, capabilityTextResource(ArCapability.Checking))
        assertEquals(R.string.ar_supported_installed, capabilityTextResource(ArCapability.SupportedInstalled))
        assertEquals(
            R.string.ar_apk_missing,
            capabilityTextResource(ArCapability.InstallRequired(InstallRequirement.ARCORE_APK_MISSING)),
        )
        assertEquals(
            R.string.ar_apk_too_old,
            capabilityTextResource(ArCapability.InstallRequired(InstallRequirement.ARCORE_APK_TOO_OLD)),
        )
        assertEquals(R.string.ar_installation_requested, capabilityTextResource(ArCapability.InstallationRequested))
        assertEquals(R.string.ar_unsupported, capabilityTextResource(ArCapability.Unsupported))
        TransientAvailabilityReason.entries.forEach { reason ->
            capabilityTextResource(ArCapability.Transient(reason))
        }
        ArUnavailableReason.entries.forEach { reason ->
            capabilityTextResource(ArCapability.Unavailable(reason))
        }
    }
}
