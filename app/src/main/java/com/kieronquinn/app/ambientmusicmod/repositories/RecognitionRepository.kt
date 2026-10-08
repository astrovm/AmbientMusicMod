package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.RemoteException
import android.os.Looper
import android.util.Log
import com.google.audio.ambientmusic.HistoryData
import com.kieronquinn.app.ambientmusicmod.repositories.RecognitionRepository.RecognitionState
import com.kieronquinn.app.ambientmusicmod.repositories.RecognitionRepository.RecognitionState.ErrorReason
import com.kieronquinn.app.ambientmusicmod.repositories.RemoteSettingsRepository.SettingsState
import com.kieronquinn.app.ambientmusicmod.utils.extensions.safeQuery
import com.kieronquinn.app.ambientmusicmod.utils.extensions.safeRegisterContentObserver
import com.kieronquinn.app.pixelambientmusic.IRecognitionCallback
import com.kieronquinn.app.pixelambientmusic.IRecognitionService
import com.kieronquinn.app.pixelambientmusic.model.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

interface RecognitionRepository {

    sealed class RecognitionState {
        data class Recording(val source: RecognitionSource): RecognitionState()
        data class Recognising(val source: RecognitionSource): RecognitionState()
        data class Recognised(
            val recognitionResult: RecognitionResult,
            val metadata: RecognitionMetadata?
        ): RecognitionState()
        data class Error(val errorReason: ErrorReason): RecognitionState()
        data class Failed(val recognitionFailure: RecognitionFailure): RecognitionState()

        enum class ErrorReason {
            SHIZUKU_ERROR, TIMEOUT, API_INCOMPATIBLE, NEEDS_ROOT, DISABLED
        }
    }

    val recognitionDialogShowing: Flow<Boolean>
    val recogniseFabClick: Flow<Unit>

    fun requestRecognition(includeAudio: Boolean = false): Flow<RecognitionState>
    fun requestOnDemandRecognition(): Flow<RecognitionState>
    fun getLatestRecognition(): Flow<LastRecognisedSong?>

    suspend fun onRecogniseFabClicked()
    suspend fun setRecognitionDialogShowing(showing: Boolean)

}

class RecognitionRepositoryImpl(
    private val ambientServiceRepository: AmbientServiceRepository,
    private val shizukuServiceRepository: ShizukuServiceRepository,
    context: Context
): RecognitionRepository, KoinComponent {

    companion object {
        private const val RECOGNITION_CALLBACK_TIMEOUT = 2500L
        private const val RECOGNITION_RESULT_TIMEOUT = 90_000L
        private val URI_HISTORY = Uri.Builder()
            .scheme("content")
            .authority("com.google.android.as.pam.ambientmusic.historyprovider")
            .path("recognizedsongs")
            .build()
        private const val COLUMN_HISTORY_TIMESTAMP = "timestamp"
        private const val COLUMN_HISTORY_HISTORY_ENTRY = "history_entry"
    }

    private val contentResolver = context.contentResolver

    private suspend fun getService() = ambientServiceRepository.getService()

    private val remoteSettings by inject<RemoteSettingsRepository>()

    private fun runRecognition(
        source: RecognitionSource,
        includeAudio: Boolean,
        requestBlock: (IRecognitionService) -> Unit
    ) = callbackFlow {
        if(!shizukuServiceRepository.assertReady()) {
            trySend(RecognitionState.Error(ErrorReason.SHIZUKU_ERROR))
            close()
            return@callbackFlow
        }
        shizukuServiceRepository.runWithService { it.isCompatible }.unwrap()?.let {
            if(!it){
                trySend(RecognitionState.Error(ErrorReason.NEEDS_ROOT))
                close()
                return@callbackFlow
            }
        }
        val settings = remoteSettings.getRemoteSettings().first()
        if(settings !is SettingsState.Available || !settings.mainEnabled){
            trySend(RecognitionState.Error(ErrorReason.DISABLED))
            close()
            return@callbackFlow
        }
        val hasStarted = AtomicBoolean(false)
        val callback = object: IRecognitionCallback.Stub() {
            override fun onRecordingStarted() {
                hasStarted.set(true)
                trySend(RecognitionState.Recording(source))
            }

            override fun onRecognitionStarted() {
                hasStarted.set(true)
                trySend(RecognitionState.Recognising(source))
            }

            override fun onRecognitionSucceeded(
                result: RecognitionResult,
                metadata: RecognitionMetadata?
            ) {
                hasStarted.set(true)
                trySend(RecognitionState.Recognised(result, metadata))
                close()
            }

            override fun onRecognitionFailed(result: RecognitionFailure) {
                hasStarted.set(true)
                trySend(RecognitionState.Failed(result))
                close()
            }
        }
        val metadata = RecognitionCallbackMetadata(source, includeAudio)
        val service = getService() ?: run {
            hasStarted.set(true)
            trySend(RecognitionState.Error(ErrorReason.API_INCOMPATIBLE))
            close()
            return@callbackFlow
        }
        val callbackId = try {
            withContext(Dispatchers.IO) { service.addRecognitionCallback(callback, metadata) }
        } catch (e: RemoteException) {
            trySend(RecognitionState.Error(ErrorReason.API_INCOMPATIBLE))
            close()
            return@callbackFlow
        }
        val watchdog = launch {
            delay(RECOGNITION_CALLBACK_TIMEOUT)
            if(!hasStarted.get()) {
                trySend(RecognitionState.Error(ErrorReason.TIMEOUT))
                close()
            } else {
                delay(RECOGNITION_RESULT_TIMEOUT - RECOGNITION_CALLBACK_TIMEOUT)
                trySend(RecognitionState.Error(ErrorReason.TIMEOUT))
                close()
            }
        }
        try {
            withContext(Dispatchers.IO) { requestBlock(service) }
        } catch (e: RemoteException) {
            trySend(RecognitionState.Error(ErrorReason.API_INCOMPATIBLE))
            close()
        }
        awaitClose {
            watchdog.cancel()
            callbackId?.let {
                //We need to disconnect regardless, even if the flow scope has gone
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        service.removeRecognitionCallback(it)
                    } catch (e: RemoteException) {
                        //The companion may have died while completing this request.
                    }
                }
            }
        }
    }

    override val recogniseFabClick = MutableSharedFlow<Unit>()
    override val recognitionDialogShowing = MutableSharedFlow<Boolean>()

    override suspend fun onRecogniseFabClicked() {
        recogniseFabClick.emit(Unit)
    }

    override suspend fun setRecognitionDialogShowing(showing: Boolean) {
        recognitionDialogShowing.emit(showing)
    }

    override fun requestRecognition(includeAudio: Boolean) = runRecognition(
        RecognitionSource.NNFP, includeAudio
    ) {
        it.requestRecognition()
    }

    override fun requestOnDemandRecognition() = runRecognition(
        RecognitionSource.ON_DEMAND, false //ON_DEMAND does not include audio
    ) {
        it.requestOnDemandRecognition()
    }

    private fun loadLatestRecognition(): LastRecognisedSong? {
        return contentResolver.safeQuery(
            URI_HISTORY,
            arrayOf(COLUMN_HISTORY_TIMESTAMP, COLUMN_HISTORY_HISTORY_ENTRY),
            null,
            null,
            "$COLUMN_HISTORY_TIMESTAMP DESC"
        )?.use { cursor ->
            if(!cursor.moveToFirst()) return@use null
            val timestamp = cursor.getLong(0)
            val historyEntry = cursor.getBlob(1)
            if(timestamp == 0L || historyEntry == null) return@use null
            val entry = try {
                HistoryData.Item.parseFrom(historyEntry)
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                return@use null
            }
            LastRecognisedSong(
                entry.track.title,
                entry.track.artist,
                timestamp,
                if(entry.source == "ON_DEMAND") RecognitionSource.ON_DEMAND else RecognitionSource.NNFP
            )
        }
    }

    override fun getLatestRecognition(): Flow<LastRecognisedSong?> = callbackFlow {
        val observer = object: ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(loadLatestRecognition())
            }
        }
        contentResolver.safeRegisterContentObserver(
            URI_HISTORY,
            true,
            observer
        )
        trySend(loadLatestRecognition())
        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

}