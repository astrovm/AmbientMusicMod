package com.kieronquinn.app.ambientmusicmod.repositories

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AmbientServiceRepositoryTest {
    private val context = mock(Context::class.java)
    @Before fun setup() {
        val api = mock(ApiRepository::class.java)
        `when`(api.assertCompatibility()).thenReturn(true)
        startKoin { modules(module { single<ApiRepository> { api } }) }
    }
    @After fun cleanup() { stopKoin() }

    @Test fun rejectedBindingReturnsImmediately() = runTest {
        `when`(context.bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt()))
            .thenReturn(false)
        assertNull(AmbientServiceRepositoryImpl(context).getService())
        verify(context, never()).unbindService(any())
    }

    @Test fun deniedBindingReturnsUnavailable() = runTest {
        `when`(context.bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt()))
            .thenThrow(SecurityException("Service permission denied"))
        assertNull(AmbientServiceRepositoryImpl(context).getService())
    }

    @Test fun missingServiceCallbackTimesOutAndUnbinds() = runTest {
        `when`(context.bindService(any(Intent::class.java), any(ServiceConnection::class.java), anyInt()))
            .thenReturn(true)
        assertNull(AmbientServiceRepositoryImpl(context).getService())
        verify(context).unbindService(any())
    }
}
