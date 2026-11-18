package com.sikw00f.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sikw00f.android.ui.AppNav
import com.sikw00f.android.ui.AppViewModel
import com.sikw00f.android.ui.SiKW00FTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogBus.init(applicationContext)
        LogBus.i("SiKW00F mobile started (session ${LogBus.sessionId}).")
        setContent {
            SiKW00FTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    AppNav(viewModel)
                }
            }
        }
    }

    // No disconnect in onDestroy: rotation destroys and recreates the Activity
    // while the ViewModel (and the radio link it owns) survives. The link is
    // closed in AppViewModel.onCleared(), which runs only when the app really exits.
}
