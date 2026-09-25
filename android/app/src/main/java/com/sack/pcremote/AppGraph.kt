package com.sack.pcremote

import android.content.Context
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.data.SettingsStore
import com.sack.pcremote.net.Discovery

/**
 * The app's singletons. Created once per process from the application context,
 * so a rotation or a new activity never builds a second credentials store or
 * mDNS client.
 */
class AppGraph private constructor(context: Context) {
    val credentials = CredentialsStore(context)
    val discovery = Discovery(context)
    val settings = SettingsStore(context)

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
