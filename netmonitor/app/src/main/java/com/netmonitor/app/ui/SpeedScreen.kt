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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netmonitor.app.TestPhase
import com.netmonitor.app.TestProgress
import com.netmonitor.app.data.SpeedTestEntity
import com.netmonitor.app.formatMbps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 속도 측정 사이트처럼 버튼을 눌러 회선 속도를 재고, 결과를 쌓아 차트로 보여 주는 탭. */
@Composable
fun SpeedScreen(vm: SpeedViewModel = viewModel()) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val latestTwo by vm.latestTwo.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SpeedTestEntity?>(null) }

    val running = progress.phase == TestPhase.PING ||
        progress.phase == TestPhase.DOWNLOAD ||
        progress.phase == TestPhase.UPLOAD

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TestCard(progress, running, onToggle = vm::toggle)

        Text(
            "한 번 측정에 데이터를 수십~수백 MB 쓸 수 있습니다. 모바일 데이터에서는 주의하세요.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PeriodChips(period) {
            vm.period.value = it
            selected = null
        }

        LatestSummary(latestTwo)

        val shown = selected
        Text(
            if (shown == null) "차트를 터치하면 그때의 측정값이 보입니다"
            else {
                val time = SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(shown.time))
                "$time · ${shown.connection} · ↓ ${formatMbps(shown.downBps)} · ↑ ${formatMbps(shown.upBps)} Mbps · " +
                    "핑 ${String.format(Locale.US, "%.0f", shown.pingMs)} ms"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val h = history
        if (h != null && h.period == period) {
            // 기록 목록이 그대로면 같은 모델을 다시 써서 줌·선택을 유지한다
            val model = remember(h) {
                val now = System.currentTimeMillis()
                ChartModel(
                    key = h.period,
                    from = h.from,
                    to = maxOf(now, h.tests.lastOrNull()?.time ?: now),
                    times = h.tests.map { it.time },
                    series = listOf(
                        ChartSeries(BlueLine, h.tests.map { (it.downBps * 8 / 1_000_000).toFloat() }),
                        ChartSeries(OrangeLine, h.tests.map { (it.upBps * 8 / 1_000_000).toFloat() }),
                    ),
                    timePattern = h.period.timePattern,
                    formatY = { String.format(Locale.US, "%.0f Mbps", it) },
                    area = false,
                )
            }
            StockChart(
                model = model,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                gridColor = MaterialTheme.colorScheme.surfaceVariant,
                onSelect = { i -> selected = i?.let { h.tests.getOrNull(it) } },
                modifier = Modifier.fillMaxWidth().height(300.dp),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(300.dp))
        }
    }
}

@Composable
private fun TestCard(progress: TestProgress, running: Boolean, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(progress.phase.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 측정 중에는 지금 재는 속도, 끝나면 다운로드 결과를 크게 보여 준다
            val big = when (progress.phase) {
                TestPhase.DOWNLOAD, TestPhase.UPLOAD -> formatMbps(progress.currentBps)
                TestPhase.DONE -> progress.downBps?.let { formatMbps(it) } ?: "-"
                else -> "-"
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(big, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.padding(start = 6.dp))
                Text(
                    "Mbps",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                ResultValue("↓ 다운로드", progress.downBps?.let { formatMbps(it) + " Mbps" }, Modifier.weight(1f))
                ResultValue("↑ 업로드", progress.upBps?.let { formatMbps(it) + " Mbps" }, Modifier.weight(1f))
                ResultValue("핑", progress.pingMs?.let { String.format(Locale.US, "%.0f ms", it) }, Modifier.weight(1f))
            }

            if (progress.phase == TestPhase.FAILED) {
                Text(
                    "측정하지 못했습니다: ${progress.error}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "중지" else "측정 시작", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ResultValue(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "-", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 주식 앱의 현재가·등락 자리: 가장 최근 다운로드 속도와 직전 측정 대비 증감. */
@Composable
private fun LatestSummary(latestTwo: List<SpeedTestEntity>) {
    val latest = latestTwo.getOrNull(0)
    val previous = latestTwo.getOrNull(1)
    Column {
        Text(
            if (latest == null) "- Mbps" else "${formatMbps(latest.downBps)} Mbps",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (latest != null && previous != null && previous.downBps > 0) {
            val diff = latest.downBps - previous.downBps
            ChangeLine(
                diffText = "${formatMbps(kotlin.math.abs(diff))} Mbps",
                diff = diff,
                percent = diff * 100 / previous.downBps,
                suffix = "직전 측정 대비 다운로드",
            )
        } else {
            ChangeLine(diffText = null, diff = 0.0, percent = 0.0, suffix = "")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(BlueLine, "다운로드")
            LegendItem(OrangeLine, "업로드")
        }
    }
}
