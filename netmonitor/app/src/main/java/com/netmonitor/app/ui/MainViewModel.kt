package com.netmonitor.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netmonitor.app.data.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

private const val MINUTE = 60_000L
private const val HOUR_MS = 60 * MINUTE
private const val DAY_MS = 24 * HOUR_MS

enum class Period(val label: String, val lengthMs: Long, val bucketMs: Long, val compareLabel: String) {
    HOUR("1시간", HOUR_MS, MINUTE, "직전 1시간 대비"),
    TODAY("오늘", DAY_MS, 5 * MINUTE, "어제 같은 시각 대비"),
    WEEK("7일", 7 * DAY_MS, 30 * MINUTE, "직전 7일 대비"),
    MONTH("30일", 30 * DAY_MS, 2 * HOUR_MS, "직전 30일 대비"),
}

/** 시각 t까지 기간 시작부터 누적된 바이트. */
data class ChartPoint(val t: Long, val wifi: Long, val mobile: Long)

class ChartData(
    val period: Period,
    val from: Long,
    val to: Long,
    val points: List<ChartPoint>,
    val prevTotal: Long,
) {
    val wifiTotal: Long get() = points.last().wifi
    val mobileTotal: Long get() = points.last().mobile
    val total: Long get() = wifiTotal + mobileTotal
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).usageDao()

    val period = MutableStateFlow(Period.TODAY)

    @OptIn(ExperimentalCoroutinesApi::class)
    val chart: StateFlow<ChartData?> = period
        .flatMapLatest { p ->
            flow {
                while (true) {
                    emit(load(p))
                    delay(10_000)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun load(p: Period): ChartData {
        val now = System.currentTimeMillis()
        val from = if (p == Period.TODAY) startOfToday() else now - p.lengthMs

        val points = ArrayList<ChartPoint>()
        points += ChartPoint(from, 0, 0)
        var wifi = 0L
        var mobile = 0L
        for (b in dao.buckets(from, now + 1, p.bucketMs)) {
            wifi += b.wifi
            mobile += b.mobile
            // 묶음이 끝나는 시각에 누적값을 찍는다
            val t = (b.t + p.bucketMs).coerceAtMost(now)
            if (t > points.last().t) points += ChartPoint(t, wifi, mobile)
            else points[points.size - 1] = ChartPoint(points.last().t, wifi, mobile)
        }
        if (points.last().t < now) points += ChartPoint(now, wifi, mobile)

        val prevTotal = dao.total(from - p.lengthMs, now - p.lengthMs)
        return ChartData(p, from, now, points, prevTotal)
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
