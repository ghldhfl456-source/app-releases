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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val BlueLine = Color(0xFF4FC3F7)
val OrangeLine = Color(0xFFFFB74D)

class ChartSeries(val color: Color, val values: List<Float>)

/** 시간 축 차트 한 장에 그릴 내용. series의 values는 times와 같은 순서·개수다. */
class ChartModel(
    /** 바뀌면 줌과 선택을 초기화한다(기간 전환 등). */
    val key: Any,
    val from: Long,
    val to: Long,
    val times: List<Long>,
    val series: List<ChartSeries>,
    val timePattern: String,
    val formatY: (Float) -> String,
    /** true면 선 아래를 채운 영역 차트, false면 점을 찍은 선 차트. */
    val area: Boolean,
)

private const val MINUTE_MS = 60_000f

/**
 * 주식 차트처럼 가로로 핀치 줌·스크롤하고, 터치하면 그 시점의 순번을 onSelect로 알려 준다.
 * x 값은 from부터 흐른 분(Float 정밀도 때문에 epoch를 그대로 쓰지 않는다).
 */
@Composable
fun StockChart(
    model: ChartModel,
    textColor: Color,
    gridColor: Color,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    // 마지막으로 그린 내용. 같은 내용으로 다시 그리면 줌·선택이 흔들리므로 건너뛴다.
    val drawn = remember { arrayOfNulls<ChartModel>(1) }
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
                        currentOnSelect(e?.data as? Int)
                    }

                    override fun onNothingSelected() {
                        currentOnSelect(null)
                    }
                })
            }
        },
        update = { chart ->
            if (drawn[0] !== model) {
                val keyChanged = drawn[0]?.key != model.key
                drawn[0] = model

                if (model.times.isEmpty()) {
                    chart.clear()
                } else {
                    val format = SimpleDateFormat(model.timePattern, Locale.getDefault())
                    chart.xAxis.valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String =
                            format.format(Date(model.from + (value * MINUTE_MS).toLong()))
                    }
                    chart.xAxis.axisMinimum = 0f
                    chart.xAxis.axisMaximum = ((model.to - model.from) / MINUTE_MS).coerceAtLeast(1f)
                    chart.axisRight.valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String = model.formatY(value)
                    }

                    val sets = model.series.map { series ->
                        val lineArgb = series.color.toArgb()
                        val entries = model.times.mapIndexed { i, t ->
                            Entry((t - model.from) / MINUTE_MS, series.values[i], i)
                        }
                        LineDataSet(entries, "").apply {
                            color = lineArgb
                            lineWidth = 2f
                            setDrawValues(false)
                            setDrawCircles(!model.area)
                            setCircleColor(lineArgb)
                            circleRadius = 3f
                            setDrawCircleHole(false)
                            setDrawFilled(model.area)
                            fillColor = lineArgb
                            fillAlpha = 40
                            highLightColor = textArgb
                            setDrawHorizontalHighlightIndicator(false)
                        }
                    }
                    chart.data = LineData(sets.toList<com.github.mikephil.charting.interfaces.datasets.ILineDataSet>())
                }
                if (keyChanged) {
                    chart.fitScreen()
                    chart.highlightValues(null)
                }
                chart.invalidate()
            }
        },
    )
}
