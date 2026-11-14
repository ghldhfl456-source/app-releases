package com.netmonitor.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.netmonitor.app.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val WifiColor = Color(0xFF4FC3F7)
val MobileColor = Color(0xFFFFB74D)

private const val MINUTE_MS = 60_000f

/**
 * 누적 사용량 차트. 가로로 핀치 줌·스크롤, 터치하면 그 시점을 onSelect로 알려 준다.
 * x 값은 기간 시작부터 흐른 분(Float 정밀도 때문에 epoch를 그대로 쓰지 않는다).
 */
@Composable
fun UsageChart(
    data: ChartData,
    textColor: Color,
    gridColor: Color,
    onSelect: (ChartPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    // 마지막으로 그린 데이터. 같은 데이터로 다시 그리면 줌·선택이 흔들리므로 건너뛴다.
    val drawn = remember { arrayOfNulls<ChartData>(1) }
    // apply 블록 안에서 차트 쪽 textColor·color 속성과 이름이 겹치지 않게 미리 뽑아 둔다
    val textArgb = textColor.toArgb()
    val gridArgb = gridColor.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("기록이 아직 없습니다")
                setNoDataTextColor(textArgb)

                setTouchEnabled(true)
                isDragEnabled = true
                isScaleXEnabled = true
                isScaleYEnabled = false
                setPinchZoom(false)
                isDoubleTapToZoomEnabled = true
                isHighlightPerTapEnabled = true
                isHighlightPerDragEnabled = true

                axisLeft.isEnabled = false
                axisRight.apply {
                    axisMinimum = 0f
                    this.textColor = textArgb
                    this.gridColor = gridArgb
                    setDrawAxisLine(false)
                    setLabelCount(5, false)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String = formatBytes(value.toDouble())
                    }
                }
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    this.textColor = textArgb
                    setDrawGridLines(false)
                    setDrawAxisLine(false)
                    setLabelCount(4, false)
                    setAvoidFirstLastClipping(true)
                }

                setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        currentOnSelect(e?.data as? ChartPoint)
                    }

                    override fun onNothingSelected() {
                        currentOnSelect(null)
                    }
                })
            }
        },
        update = { chart ->
            if (drawn[0] !== data) {
                val periodChanged = drawn[0]?.period != data.period
                drawn[0] = data

                fun entries(value: (ChartPoint) -> Long) =
                    data.points.map { Entry((it.t - data.from) / MINUTE_MS, value(it).toFloat(), it) }

                fun dataSet(entries: List<Entry>, label: String, lineArgb: Int) =
                    LineDataSet(entries, label).apply {
                        color = lineArgb
                        lineWidth = 2f
                        setDrawCircles(false)
                        setDrawValues(false)
                        setDrawFilled(true)
                        fillColor = lineArgb
                        fillAlpha = 40
                        highLightColor = textArgb
                        setDrawHorizontalHighlightIndicator(false)
                    }

                val pattern = if (data.period == Period.HOUR || data.period == Period.TODAY) "HH:mm" else "M/d"
                val format = SimpleDateFormat(pattern, Locale.getDefault())
                chart.xAxis.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String =
                        format.format(Date(data.from + (value * MINUTE_MS).toLong()))
                }
                chart.xAxis.axisMinimum = 0f
                chart.xAxis.axisMaximum = (data.to - data.from) / MINUTE_MS

                chart.data = LineData(
                    dataSet(entries { it.wifi }, "Wi-Fi", WifiColor.toArgb()),
                    dataSet(entries { it.mobile }, "모바일", MobileColor.toArgb()),
                )
                if (periodChanged) {
                    chart.fitScreen()
                    chart.highlightValues(null)
                }
                chart.invalidate()
            }
        },
    )
}
