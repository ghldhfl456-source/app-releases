package com.netmonitor.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netmonitor.app.SpeedTester
import com.netmonitor.app.TestPhase
import com.netmonitor.app.TestProgress
import com.netmonitor.app.currentConnection
import com.netmonitor.app.data.AppDatabase
import com.netmonitor.app.data.SpeedTestEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 한 기간의 측정 기록. from은 조회를 시작한 시각 기준 기간 시작. */
class SpeedHistory(val period: Period, val from: Long, val tests: List<SpeedTestEntity>)

class SpeedViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).speedTestDao()
    private var job: Job? = null

    val progress = MutableStateFlow(TestProgress())
    val period = MutableStateFlow(Period.WEEK)

    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<SpeedHistory?> = period
        .flatMapLatest { p ->
            val from = p.startAt(System.currentTimeMillis())
            dao.since(from).map { SpeedHistory(p, from, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 최신이 먼저인 최근 두 번의 측정. */
    val latestTwo: StateFlow<List<SpeedTestEntity>> =
        dao.latestTwo().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isRunning: Boolean get() = job?.isActive == true

    /** 측정 중이면 멈추고, 아니면 시작한다. */
    fun toggle() {
        if (isRunning) {
            job?.cancel()
            progress.value = TestProgress()
            return
        }
        job = viewModelScope.launch {
            try {
                val connection = currentConnection(getApplication())
                val result = SpeedTester.run { progress.value = it }
                dao.insert(
                    SpeedTestEntity(
                        time = System.currentTimeMillis(),
                        connection = connection.label,
                        downBps = result.downBps ?: 0.0,
                        upBps = result.upBps ?: 0.0,
                        pingMs = result.pingMs ?: 0.0,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.value = TestProgress(phase = TestPhase.FAILED, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
