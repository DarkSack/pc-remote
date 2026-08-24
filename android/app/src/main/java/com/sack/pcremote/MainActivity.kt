package com.sack.pcremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.sack.pcremote.data.CredentialsStore
import com.sack.pcremote.net.Discovery
import com.sack.pcremote.ui.PcRemoteApp
import com.sack.pcremote.ui.theme.PcRemoteTheme
import com.sack.pcremote.ui.theme.BgDark

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store     = CredentialsStore(applicationContext)
        val discovery = Discovery(applicationContext)

        setContent {
            PcRemoteTheme {
                Surface(
                    color = BgDark,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    val nav = rememberNavController()
                    PcRemoteApp(nav = nav, store = remember { store }, discovery = remember { discovery })
                }
            }
        }
    }
}
