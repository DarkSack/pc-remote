package com.sack.pcremote.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.Discovery

/** Owns the [PcSession] of one PC for as long as its screens are on the back stack. */
class PcViewModel(
    app: Application,
    store: CredentialsStore,
    discovery: Discovery,
    deviceId: String,
) : AndroidViewModel(app) {

    /** Null when the PC is no longer paired (deleted meanwhile): the screen goes back. */
    val session: PcSession? = store.load(deviceId)?.let { PcSession(app, store, discovery, it, viewModelScope) }

    override fun onCleared() {
        session?.close()
    }

    class Factory(
        private val store: CredentialsStore,
        private val discovery: Discovery,
        private val deviceId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
            return PcViewModel(app, store, discovery, deviceId) as T
        }
    }
}
