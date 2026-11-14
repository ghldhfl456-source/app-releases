package com.netmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 한국 주식 앱처럼 늘면 빨강, 줄면 파랑
val UpColor = Color(0xFFFF5252)
val DownColor = Color(0xFF448AFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodChips(selected: Period, onSelect: (Period) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Period.entries.forEach { p ->
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.label) })
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 큰 숫자 아래에 붙는 증감 한 줄. previous가 없으면 안내 문구만 보인다. */
@Composable
fun ChangeLine(diffText: String?, diff: Double, percent: Double, suffix: String) {
    if (diffText == null) {
        Text("비교할 이전 기록 없음", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val arrow = if (diff > 0) "▲" else if (diff < 0) "▼" else "-"
    val color = if (diff > 0) UpColor else if (diff < 0) DownColor else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        "$arrow $diffText (${String.format(java.util.Locale.US, "%+.1f", percent)}%) · $suffix",
        fontSize = 13.sp,
        color = color,
    )
}
