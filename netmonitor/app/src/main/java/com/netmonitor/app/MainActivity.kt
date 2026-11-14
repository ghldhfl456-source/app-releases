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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netmonitor.app.service.MonitorService
import com.netmonitor.app.ui.ChartData
import com.netmonitor.app.ui.ChartPoint
import com.netmonitor.app.ui.MainViewModel
import com.netmonitor.app.ui.MobileColor
import com.netmonitor.app.ui.Period
import com.netmonitor.app.ui.UsageChart
import com.netmonitor.app.ui.WifiColor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Colors = darkColorScheme(
    primary = WifiColor,
    background = Color(0xFF0F1115),
    surface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFF1A1E26),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF9AA0A6),
)

// 한국 주식 앱처럼 늘면 빨강, 줄면 파랑
private val UpColor = Color(0xFFFF5252)
private val DownColor = Color(0xFF448AFF)

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
private fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.isEnabled(context)) }
    val live by MonitorService.state.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ChartPoint?>(null) }

    // 서비스가 꺼져 있을 때도 연결 종류는 보여 준다
    val connection by produceState(currentConnection(context)) {
        while (true) {
            value = currentConnection(context)
            delay(1000)
        }
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PowerCard(
                enabled = enabled,
                onToggle = { on ->
                    enabled = on
                    Prefs.setEnabled(context, on)
                    if (on) startWithPermission() else MonitorService.stop(context)
                },
            )

            LiveCard(
                running = live.running,
                connection = if (live.running) live.connection else connection,
                down = live.downBps,
                up = live.upBps,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p ->
                    PeriodChip(p, selected = p == period) {
                        vm.period.value = p
                        selected = null
                    }
                }
            }

            val data = chart
            if (data != null && data.period == period) {
                Summary(data)
                SelectedLine(selected)
                UsageChart(
                    data = data,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    gridColor = MaterialTheme.colorScheme.surfaceVariant,
                    onSelect = { selected = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(320.dp))
            }
        }
    }
}

@Composable
private fun PowerCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("측정·기록", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (enabled) "ON · 앱을 닫아도 계속 기록합니다" else "OFF · 측정과 기록을 멈췄습니다",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun LiveCard(running: Boolean, connection: Connection, down: Double, up: Double) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(connection.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                SpeedValue("↓ 다운로드", if (running) formatSpeed(down) else "-", Modifier.weight(1f))
                SpeedValue("↑ 업로드", if (running) formatSpeed(up) else "-", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpeedValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodChip(period: Period, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(period.label) })
}

/** 주식 앱의 현재가·등락 자리: 기간 총 사용량과 이전 기간 대비 증감. */
@Composable
private fun Summary(data: ChartData) {
    Column {
        Text(
            formatBytes(data.total.toDouble()),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val diff = data.total - data.prevTotal
        if (data.prevTotal <= 0) {
            Text("비교할 이전 기록 없음", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val percent = diff * 100.0 / data.prevTotal
            val arrow = if (diff > 0) "▲" else if (diff < 0) "▼" else "-"
            val color = if (diff > 0) UpColor else if (diff < 0) DownColor else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                "$arrow ${formatBytes(kotlin.math.abs(diff).toDouble())} " +
                    "(${String.format(Locale.US, "%+.1f", percent)}%) · ${data.period.compareLabel}",
                fontSize = 13.sp,
                color = color,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(WifiColor, "Wi-Fi ${formatBytes(data.wifiTotal.toDouble())}")
            LegendItem(MobileColor, "모바일 ${formatBytes(data.mobileTotal.toDouble())}")
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 차트에서 터치한 시점의 누적값. */
@Composable
private fun SelectedLine(point: ChartPoint?) {
    val text = if (point == null) {
        "차트를 터치하면 그 시점의 값이 보입니다"
    } else {
        val time = SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(point.t))
        "$time · Wi-Fi ${formatBytes(point.wifi.toDouble())} · 모바일 ${formatBytes(point.mobile.toDouble())}"
    }
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
