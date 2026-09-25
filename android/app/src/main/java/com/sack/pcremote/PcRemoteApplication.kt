package com.sack.pcremote

import android.app.Application
import com.sack.pcremote.data.AppSettings
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.Discovery

/**
 * App-wide singletons. Small enough not to need a DI framework: ViewModels
 * reach them through the Application they already receive.
 */
class PcRemoteApplication : Application() {
    val store by lazy { CredentialsStore(this) }
    val discovery by lazy { Discovery(this) }
    val settings by lazy { AppSettings(this) }
}
