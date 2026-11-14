package com.netmonitor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netmonitor.app.Connection
import com.netmonitor.app.currentConnection
import com.netmonitor.app.formatBytes
import com.netmonitor.app.formatSpeed
import com.netmonitor.app.service.MonitorService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 지금 실제로 오가는 속도와, 쌓인 사용량 차트를 보여 주는 탭. */
@Composable
fun UsageScreen(enabled: Boolean, onToggle: (Boolean) -> Unit, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PowerCard(enabled, onToggle)

        LiveCard(
            running = live.running,
            connection = if (live.running) live.connection else connection,
            down = live.downBps,
            up = live.upBps,
        )

        PeriodChips(period) {
            vm.period.value = it
            selected = null
        }

        val data = chart
        if (data != null && data.period == period) {
            Summary(data)

            val shown = selected
            Text(
                if (shown == null) "차트를 터치하면 그 시점의 값이 보입니다"
                else {
                    val time = SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(shown.t))
                    "$time · Wi-Fi ${formatBytes(shown.wifi.toDouble())} · 모바일 ${formatBytes(shown.mobile.toDouble())}"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val model = remember(data) {
                ChartModel(
                    key = data.period,
                    from = data.from,
                    to = data.to,
                    times = data.points.map { it.t },
                    series = listOf(
                        ChartSeries(BlueLine, data.points.map { it.wifi.toFloat() }),
                        ChartSeries(OrangeLine, data.points.map { it.mobile.toFloat() }),
                    ),
                    timePattern = data.period.timePattern,
                    formatY = { formatBytes(it.toDouble()) },
                    area = true,
                )
            }
            StockChart(
                model = model,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                gridColor = MaterialTheme.colorScheme.surfaceVariant,
                onSelect = { i -> selected = i?.let { data.points.getOrNull(it) } },
                modifier = Modifier.fillMaxWidth().height(300.dp),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(300.dp))
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
                Text("사용량 기록", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (enabled) "ON · 앱을 닫아도 계속 기록합니다" else "OFF · 기록과 알림을 멈췄습니다",
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
            Text(
                "${connection.label} · 지금 오가는 데이터",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        if (data.prevTotal > 0) {
            val diff = (data.total - data.prevTotal).toDouble()
            ChangeLine(
                diffText = formatBytes(kotlin.math.abs(diff)),
                diff = diff,
                percent = diff * 100 / data.prevTotal,
                suffix = data.period.compareLabel,
            )
        } else {
            ChangeLine(diffText = null, diff = 0.0, percent = 0.0, suffix = "")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(BlueLine, "Wi-Fi ${formatBytes(data.wifiTotal.toDouble())}")
            LegendItem(OrangeLine, "모바일 ${formatBytes(data.mobileTotal.toDouble())}")
        }
    }
}
