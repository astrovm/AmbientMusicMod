package com.kieronquinn.app.ambientmusicmod.repositories

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AccessibilityRepositoryTest {
    private val full = "com.kieronquinn.app.ambientmusicmod/com.kieronquinn.app.ambientmusicmod.service.LockscreenOverlayAccessibilityService"
    private val short = "com.kieronquinn.app.ambientmusicmod/.service.LockscreenOverlayAccessibilityService"
    @Test fun fullAndShortComponentNamesAreEquivalent() {
        assertTrue(AccessibilityRepositoryImpl.isServiceEnabled(full))
        assertTrue(AccessibilityRepositoryImpl.isServiceEnabled(short))
    }
    @Test fun mixedOemSeparatorsAreAccepted() {
        assertTrue(AccessibilityRepositoryImpl.isServiceEnabled("other/.Service,$short:another/.Service"))
    }
    @Test fun missingMalformedAndOtherComponentsAreDisabled() {
        for(value in listOf(null, "", "invalid", "other/.Service", "$short.extra")) {
            assertFalse(AccessibilityRepositoryImpl.isServiceEnabled(value))
        }
    }
}
