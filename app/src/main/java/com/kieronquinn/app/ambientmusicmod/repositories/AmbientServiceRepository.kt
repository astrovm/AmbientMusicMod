package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.pixelambientmusic.IRecognitionService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

interface AmbientServiceRepository {

    suspend fun getService(): IRecognitionService?

}

class AmbientServiceRepositoryImpl(
    private val context: Context
): AmbientServiceRepository {

    @Volatile private var service: IRecognitionService? = null
    @Volatile private var serviceConnection: ServiceConnection? = null
    private val serviceLock = Mutex()

    private val serviceIntent by lazy {
        Intent("com.kieronquinn.app.pixelambientmusic.RECOGNITION_SERVICE").apply {
            `package` = PACKAGE_NAME_PAM
        }
    }

    override suspend fun getService() = serviceLock.withLock {
        if(!ApiRepository.assertCompatibility()) return@withLock null
        service?.let {
            if(!it.safePing()) return@let
            return@withLock it
        }
        service = null
        serviceConnection?.let { unbind(it) }
        withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine<IRecognitionService?> { continuation ->
                val connection = object: ServiceConnection {
                    override fun onServiceConnected(component: ComponentName, binder: IBinder) {
                        if(!continuation.isActive || serviceConnection !== this) {
                            unbind(this)
                            return
                        }
                        val connected = IRecognitionService.Stub.asInterface(binder)
                        service = connected
                        serviceConnection = this
                        continuation.resume(connected)
                    }

                    override fun onServiceDisconnected(component: ComponentName) {
                        if(serviceConnection === this) service = null
                        if(continuation.isActive) continuation.resume(null)
                    }

                    override fun onBindingDied(component: ComponentName) {
                        unbind(this)
                        if(continuation.isActive) continuation.resume(null)
                    }

                    override fun onNullBinding(component: ComponentName) {
                        unbind(this)
                        if(continuation.isActive) continuation.resume(null)
                    }
                }
                serviceConnection = connection
                val bound = try {
                    context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    false
                }
                if(!bound) {
                    if(serviceConnection === connection) serviceConnection = null
                    if(continuation.isActive) continuation.resume(null)
                } else {
                    continuation.invokeOnCancellation { unbind(connection) }
                }
            }
        }
    }

    private fun unbind(connection: ServiceConnection) {
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            //Binding may already have been released by Android or cancellation.
        }
        if(serviceConnection === connection) {
            serviceConnection = null
            service = null
        }
    }

    private fun IRecognitionService.safePing(): Boolean {
        return try {
            ping()
        }catch (e: RemoteException){
            false
        }
    }

}