package com.kieronquinn.app.ambientmusicmod.ui.screens.setup.installpam

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.ambientmusicmod.repositories.ApiRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InstalledCompanionTest {
    private val context = mock(Context::class.java)
    private val manager = mock(PackageManager::class.java)
    private val info = PackageInfo().apply {
        versionName = "1.4.0"
        longVersionCode = 140L
        applicationInfo = ApplicationInfo().apply {
            metaData = Bundle().apply { putInt(ApiRepository.API_VERSION_TAG, 2) }
        }
    }
    @Before fun setup() {
        `when`(context.packageName).thenReturn("com.example.host")
        `when`(context.packageManager).thenReturn(manager)
        `when`(manager.checkSignatures(anyString(), anyString())).thenReturn(PackageManager.SIGNATURE_MATCH)
        `when`(manager.getPackageInfo(eq(PACKAGE_NAME_PAM), any(PackageManager.PackageInfoFlags::class.java))).thenReturn(info)
    }
    @Test fun matchingInstalledCompanionCanCompleteSetupWithoutARelease() {
        val result = SetupInstallPAMViewModelImpl.compatibleInstalledCompanion(context)
        assertNotNull(result)
        assertEquals("1.4.0", result!!.localVersion)
        assertEquals(140L, result.localVersionCode)
    }
    @Test fun differentCertificateCannotSkipInstallation() {
        `when`(manager.checkSignatures(anyString(), anyString())).thenReturn(PackageManager.SIGNATURE_NO_MATCH)
        assertNull(SetupInstallPAMViewModelImpl.compatibleInstalledCompanion(context))
    }
    @Test fun unsupportedApiCannotSkipInstallation() {
        info.applicationInfo!!.metaData.putInt(ApiRepository.API_VERSION_TAG, 99)
        assertNull(SetupInstallPAMViewModelImpl.compatibleInstalledCompanion(context))
    }
    @Test fun missingMetadataCannotSkipInstallation() {
        info.applicationInfo!!.metaData = null
        assertNull(SetupInstallPAMViewModelImpl.compatibleInstalledCompanion(context))
    }
    @Test fun missingPackageCannotSkipInstallation() {
        `when`(manager.getPackageInfo(eq(PACKAGE_NAME_PAM), any(PackageManager.PackageInfoFlags::class.java)))
            .thenThrow(PackageManager.NameNotFoundException())
        assertNull(SetupInstallPAMViewModelImpl.compatibleInstalledCompanion(context))
    }
}
