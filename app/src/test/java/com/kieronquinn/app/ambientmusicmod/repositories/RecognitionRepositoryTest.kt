package com.kieronquinn.app.ambientmusicmod.repositories

import android.app.Application
import android.content.Context
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.flow.first
import android.os.DeadObjectException
import com.kieronquinn.app.ambientmusicmod.IShellProxy
import com.kieronquinn.app.pixelambientmusic.IRecognitionCallback
import com.kieronquinn.app.pixelambientmusic.model.RecognitionResult
import com.kieronquinn.app.pixelambientmusic.model.RecognitionSource
import com.kieronquinn.app.pixelambientmusic.IRecognitionService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RecognitionRepositoryTest {
    private val remote = mock(RemoteSettingsRepository::class.java)
    private val companion = mock(IRecognitionService::class.java)
    private var serviceRequests = 0
    private val ambient = object : AmbientServiceRepository {
        override suspend fun getService(): IRecognitionService {
            serviceRequests++
            return companion
        }
    }
    private val shell = mock(IShellProxy::class.java)
    private val shizuku = object : ShizukuServiceRepository {
        override val isReady = flowOf(true)
        override suspend fun assertReady() = true
        override suspend fun <T> runWithService(block: (IShellProxy) -> T) =
            ShizukuServiceRepository.ShizukuServiceResponse.Success(block(shell))
        override fun <T> runWithServiceIfAvailable(block: (IShellProxy) -> T) =
            ShizukuServiceRepository.ShizukuServiceResponse.Success(block(shell))
        override fun disconnect() = Unit
    }

    @Before fun setup() {
        `when`(shell.isCompatible).thenReturn(true)
        setEnabled(true)
        val api = mock(ApiRepository::class.java)
        `when`(api.assertCompatibility()).thenReturn(true)
        startKoin { modules(module {
            single<RemoteSettingsRepository> { remote }
            single<ApiRepository> { api }
        }) }
    }
    @After fun cleanup() { stopKoin() }
    private fun setEnabled(enabled: Boolean) {
        `when`(remote.getRemoteSettings()).thenReturn(flowOf(
            RemoteSettingsRepository.SettingsState.Available(enabled, false, true, null, null, false)
        ))
    }
    private fun TestScope.repository() = RecognitionRepositoryImpl(ambient, shizuku,
        RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler))

    @Test fun disabledRecognitionNeverContactsCompanion() = runTest {
        setEnabled(false)
        val result = repository().requestRecognition().toList()
        assertEquals(listOf(RecognitionRepository.RecognitionState.Error(
            RecognitionRepository.RecognitionState.ErrorReason.DISABLED)), result)
        assertEquals(0, serviceRequests)
        verifyNoInteractions(companion)
    }

    @Test fun companionDeathDuringCallbackRegistrationReportsError() = runTest {
        `when`(companion.addRecognitionCallback(any(), any())).thenThrow(DeadObjectException())
        val result = repository().requestRecognition().toList()
        assertEquals(listOf(RecognitionRepository.RecognitionState.Error(
            RecognitionRepository.RecognitionState.ErrorReason.API_INCOMPATIBLE)), result)
    }

    @Test fun companionDeathDuringRequestReportsError() = runTest {
        doThrow(DeadObjectException()).`when`(companion).requestRecognition()
        val result = repository().requestRecognition().toList()
        assertEquals(listOf(RecognitionRepository.RecognitionState.Error(
            RecognitionRepository.RecognitionState.ErrorReason.API_INCOMPATIBLE)), result)
    }

    @Test fun missingCallbacksTimeOut() = runTest {
        val result = repository().requestRecognition().toList()
        assertEquals(listOf(RecognitionRepository.RecognitionState.Error(
            RecognitionRepository.RecognitionState.ErrorReason.TIMEOUT)), result)
    }
    @Test fun startedRecognitionWithoutResultStillTimesOut() = runTest {
        `when`(companion.addRecognitionCallback(any(), any())).thenAnswer { call ->
            call.getArgument<IRecognitionCallback>(0).onRecognitionStarted()
            null
        }
        val result = repository().requestRecognition().toList()
        assertTrue(result.first() is RecognitionRepository.RecognitionState.Recognising)
        assertEquals(RecognitionRepository.RecognitionState.Error(
            RecognitionRepository.RecognitionState.ErrorReason.TIMEOUT), result.last())
    }

    @Test fun successfulRecognitionCompletesWithoutTimeout() = runTest {
        val song = RecognitionResult("Test song", "Test artist", RecognitionSource.NNFP,
            emptyArray(), null, null)
        `when`(companion.addRecognitionCallback(any(), any())).thenAnswer { call ->
            call.getArgument<IRecognitionCallback>(0).onRecognitionSucceeded(song, null)
            null
        }
        val result = repository().requestRecognition().toList()
        assertEquals(listOf(RecognitionRepository.RecognitionState.Recognised(song, null)), result)
    }

    private suspend fun readHistory(cursor: MatrixCursor) {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.query(any(Uri::class.java), any(), isNull(), isNull(), anyString()))
            .thenReturn(cursor)
        assertNull(RecognitionRepositoryImpl(ambient, shizuku, context).getLatestRecognition().first())
        assertTrue(cursor.isClosed)
    }

    @Test fun emptyHistoryClosesCursor() = runTest {
        readHistory(MatrixCursor(arrayOf("timestamp", "history_entry")))
    }

    @Test fun corruptHistoryClosesCursorAndReturnsNoSong() = runTest {
        readHistory(MatrixCursor(arrayOf("timestamp", "history_entry")).apply {
            addRow(arrayOf(123L, byteArrayOf(0xff.toByte())))
        })
    }

}
