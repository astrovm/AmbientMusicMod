package com.kieronquinn.app.ambientmusicmod.ui.screens.setup.installpam

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.components.navigation.SetupNavigation
import com.kieronquinn.app.ambientmusicmod.model.update.toRelease
import com.kieronquinn.app.ambientmusicmod.repositories.ApiRepository
import com.kieronquinn.app.ambientmusicmod.utils.extensions.getPackageInfoCompat
import com.kieronquinn.app.ambientmusicmod.repositories.UpdatesRepository
import com.kieronquinn.app.ambientmusicmod.repositories.UpdatesRepository.UpdateState
import com.kieronquinn.app.ambientmusicmod.utils.extensions.broadcastReceiverAsFlow
import com.kieronquinn.app.ambientmusicmod.utils.extensions.contentReceiverAsFlow
import com.kieronquinn.app.ambientmusicmod.utils.extensions.isAppInstalled
import com.kieronquinn.app.ambientmusicmod.utils.extensions.onPackageChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

abstract class SetupInstallPAMViewModel: ViewModel() {

    abstract val state: StateFlow<State>

    abstract fun restartDownload()
    abstract fun moveToNext()
    abstract fun onInstallClicked()

    sealed class State {
        object Loading: State()
        data class Downloading(val progress: Double): State()
        object Error: State()
        object DownloadComplete: State()
        object Installed: State()
    }

}

class SetupInstallPAMViewModelImpl(
    context: Context,
    private val updatesRepository: UpdatesRepository,
    private val navigation: SetupNavigation
): SetupInstallPAMViewModel() {

    companion object {
        internal fun compatibleInstalledCompanion(context: Context): UpdateState.UpToDate? {
            val manager = context.packageManager
            if(manager.checkSignatures(context.packageName, PACKAGE_NAME_PAM) != PackageManager.SIGNATURE_MATCH) return null
            val info = try {
                manager.getPackageInfoCompat(PACKAGE_NAME_PAM, PackageManager.GET_META_DATA)
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
            val api = info.applicationInfo?.metaData?.getInt(ApiRepository.API_VERSION_TAG) ?: return null
            if(api !in ApiRepository.COMPATIBLE_APIS) return null
            return UpdateState.UpToDate(info.versionName ?: return null, info.longVersionCode)
        }
    }

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val getUriForFile = { downloadFile: File ->
        FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", downloadFile)
    }

    private val downloadManagerTitle = context.getString(R.string.app_name)
    private val downloadManagerDescription = context.getString(R.string.download_manager_description_initial)

    private val downloadFolder = updatesRepository.getUpdatesFolder(context).apply {
        //Clear the updates folder on start to remove unfinished downloads
        updatesRepository.clearUpdatesFolder(this)
    }

    private val downloadBus = MutableStateFlow(System.currentTimeMillis())
    private val downloadId = MutableStateFlow<Pair<Long, String>?>(null)

    private val updateState = downloadBus.mapLatest {
        compatibleInstalledCompanion(context) ?: updatesRepository.getPAMUpdateState(true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null).onEach {
        val release = when(it){
            is UpdateState.NotInstalled -> it.release.toRelease("", "")
            is UpdateState.UpdateAvailable -> it.release.toRelease("", "")
            else -> null
        } ?: return@onEach
        //Start the download with this new release
        queueDownload(release.downloadUrl, release.fileName)
    }.flowOn(Dispatchers.IO)

    private val downloadProgress = combine(
        downloadId,
        context.contentReceiverAsFlow(Uri.parse("content://downloads/my_downloads"))
    ) { id, _ ->
        getDownloadProgress(id?.first ?: return@combine null)
    }.flowOn(Dispatchers.IO)

    private val downloadComplete = downloadId.flatMapLatest {
        flow {
            emit(null)
            if(it == null) return@flow
            context.broadcastReceiverAsFlow(DownloadManager.ACTION_DOWNLOAD_COMPLETE).first()
            emit(getDownloadSuccess(it.first))
        }
    }.flowOn(Dispatchers.IO)

    private val installComplete = downloadId.flatMapLatest {
        flow {
            emit(null)
            if(it == null) return@flow
            //Wait for next package changed event
            context.onPackageChanged(PACKAGE_NAME_PAM, false).first()
            emit(context.packageManager.isAppInstalled(PACKAGE_NAME_PAM))
        }
    }

    override val state = combine(
        updateState,
        downloadProgress,
        downloadComplete,
        installComplete
    ) { update, progress, complete, installed ->
        //If update is null, we're still loading
        if(update == null) return@combine State.Loading
        //If already up to date or installed, we're done
        if(update is UpdateState.UpToDate || installed == true) return@combine State.Installed
        //If failed to fetch, show error
        if(update is UpdateState.FailedToFetchInitial) return@combine State.Error
        if(update is UpdateState.FailedToFetchUpdate) return@combine State.Error
        //If complete isn't null, emit either complete or error
        complete?.let {
            return@combine if(it) State.DownloadComplete else State.Error
        }
        //If progress isn't null, emit the current download progress
        progress?.let {
            return@combine State.Downloading(it)
        }
        //If both progress and complete are null, we're awaiting the start of a download
        State.Loading
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State.Loading)

    private fun getDownloadProgress(requestId: Long): Double {
        val query = DownloadManager.Query()
        query.setFilterById(requestId)
        return downloadManager.query(query)?.use { cursor ->
            if(!cursor.moveToFirst()) return@use 0.0
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            if(size <= 0L) 0.0 else (downloaded * 100.0 / size).coerceIn(0.0, 100.0)
        } ?: 0.0
    }

    private fun getDownloadSuccess(requestId: Long): Boolean {
        val query = DownloadManager.Query().apply { setFilterById(requestId) }
        return downloadManager.query(query)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false
    }

    override fun restartDownload() {
        viewModelScope.launch {
            downloadBus.emit(System.currentTimeMillis())
        }
    }

    private suspend fun queueDownload(url: String, fileName: String) {
        val downloadFile = File(downloadFolder, fileName)
        val requestId = downloadManager.enqueue(DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(downloadManagerTitle)
            setDescription(downloadManagerDescription)
            setDestinationUri(Uri.fromFile(downloadFile))
        })
        downloadId.emit(Pair(requestId, fileName))
    }

    override fun moveToNext() {
        viewModelScope.launch {
            navigation.navigate(SetupInstallPAMFragmentDirections.actionSetupInstallPAMFragmentToSetupPermissionsFragment())
        }
    }

    override fun onInstallClicked() {
        val fileName = downloadId.value?.second ?: return
        val file = File(downloadFolder, fileName)
        if(!file.exists()) return
        val uri = getUriForFile(file)
        viewModelScope.launch {
            Intent(Intent.ACTION_VIEW, uri).apply {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.also {
                navigation.navigate(it)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadId.value?.let {
            downloadManager.remove(it.first)
        }
        updatesRepository.clearUpdatesFolder(downloadFolder)
    }

}