package com.kieronquinn.app.ambientmusicmod.repositories

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShizukuServiceRepositoryTest {
    @Test fun detachedShizukuClientDoesNotCrash() = runTest {
        mockStatic(Shizuku::class.java).use { mocked ->
            mocked.`when`<Boolean> { Shizuku.pingBinder() }.thenReturn(true)
            mocked.`when`<Int> { Shizuku.checkSelfPermission() }
                .thenThrow(IllegalStateException("Not an attached client"))
            val repository = ShizukuServiceRepositoryImpl(
                mock(SettingsRepository::class.java), RuntimeEnvironment.getApplication())
            val result = repository.runWithService { it.ping() }
            assertEquals(ShizukuServiceRepository.ShizukuServiceResponse.Failed<Boolean>(
                ShizukuServiceRepository.ShizukuServiceResponse.FailureReason.NO_BINDER), result)
        }
    }
}
