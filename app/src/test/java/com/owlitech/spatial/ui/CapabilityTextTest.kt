package com.owlitech.spatial.ui

import com.owlitech.spatial.R
import com.owlitech.spatial.ar.ArCapability
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityTextTest {
    @Test
    fun allPublicStatesHaveStableTextResources() {
        assertEquals(R.string.ar_checking, capabilityTextResource(ArCapability.Checking))
        assertEquals(R.string.ar_supported_installed, capabilityTextResource(ArCapability.SupportedInstalled))
        assertEquals(R.string.ar_supported_not_installed, capabilityTextResource(ArCapability.SupportedNotInstalled))
        assertEquals(R.string.ar_unsupported, capabilityTextResource(ArCapability.Unsupported))
        assertEquals(R.string.ar_transient, capabilityTextResource(ArCapability.Transient))
        assertEquals(R.string.ar_error, capabilityTextResource(ArCapability.Error("test")))
    }
}
