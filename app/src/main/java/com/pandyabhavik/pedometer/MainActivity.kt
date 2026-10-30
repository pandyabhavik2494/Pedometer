package com.pandyabhavik.pedometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandyabhavik.pedometer.data.ThemeMode
import com.pandyabhavik.pedometer.sensor.StepReader
import com.pandyabhavik.pedometer.ui.AppRoot
import com.pandyabhavik.pedometer.ui.DaysViewModel
import com.pandyabhavik.pedometer.ui.MainViewModel
import com.pandyabhavik.pedometer.ui.theme.PedometerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val days: DaysViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(StepReader.Live(this))
        setContent {
            val settings by main.settings.collectAsStateWithLifecycle()
            PedometerTheme(settings?.theme ?: ThemeMode.SYSTEM) {
                AppRoot(main, days)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        main.onAppVisible()
    }
}
