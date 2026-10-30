package com.pandyabhavik.pedometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pandyabhavik.pedometer.ui.PrivacyPolicyScreen
import com.pandyabhavik.pedometer.ui.theme.PedometerTheme
import dagger.hilt.android.AndroidEntryPoint

/** Opened by Health Connect ("Why does this app need access?") and from Settings. */
@AndroidEntryPoint
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PedometerTheme { PrivacyPolicyScreen(onBack = { finish() }) }
        }
    }
}
