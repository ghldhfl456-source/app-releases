package com.netmonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.netmonitor.app.service.MonitorService
import com.netmonitor.app.ui.BlueLine
import com.netmonitor.app.ui.SpeedScreen
import com.netmonitor.app.ui.UsageScreen

private val Colors = darkColorScheme(
    primary = BlueLine,
    onPrimary = Color(0xFF00222E),
    background = Color(0xFF0F1115),
    surface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFF1A1E26),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF9AA0A6),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = Colors) {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(Prefs.isEnabled(context)) }

    // 알림 권한을 거부해도 기록은 된다(알림만 안 보임)
    val askNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (enabled) MonitorService.start(context)
    }

    fun startWithPermission() {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsAsk) askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        else MonitorService.start(context)
    }

    LaunchedEffect(Unit) {
        if (enabled) startWithPermission()
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("속도 측정") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("사용량") })
            }
            if (tab == 0) {
                SpeedScreen()
            } else {
                UsageScreen(
                    enabled = enabled,
                    onToggle = { on ->
                        enabled = on
                        Prefs.setEnabled(context, on)
                        if (on) startWithPermission() else MonitorService.stop(context)
                    },
                )
            }
        }
    }
}
