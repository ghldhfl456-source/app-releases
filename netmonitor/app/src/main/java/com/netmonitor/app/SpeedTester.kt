package com.netmonitor.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class TestPhase(val label: String) {
    IDLE("대기"),
    PING("핑 측정 중"),
    DOWNLOAD("다운로드 측정 중"),
    UPLOAD("업로드 측정 중"),
    DONE("측정 완료"),
    FAILED("측정 실패"),
}

data class TestProgress(
    val phase: TestPhase = TestPhase.IDLE,
    /** 지금 단계에서 재고 있는 속도(초당 바이트). */
    val currentBps: Double = 0.0,
    val pingMs: Double? = null,
    val downBps: Double? = null,
    val upBps: Double? = null,
    val error: String? = null,
)

/**
 * 속도 측정 사이트와 같은 방식: 서버와 여러 연결로 일정 시간 동안 최대한 주고받아
 * 회선이 낼 수 있는 속도를 잰다. 서버는 Cloudflare 공개 속도 측정 주소를 쓴다.
 */
object SpeedTester {
    private const val BASE = "https://speed.cloudflare.com"
    private const val CONNECTIONS = 4
    private const val WARMUP_MS = 1_500L   // 연결·버퍼가 안정될 때까지는 계산에서 뺀다
    private const val MEASURE_MS = 7_000L
    private const val DOWN_CHUNK = 50_000_000L
    private const val UP_CHUNK = 20_000_000

    suspend fun run(onProgress: (TestProgress) -> Unit): TestProgress = withContext(Dispatchers.IO) {
        var p = TestProgress(phase = TestPhase.PING)
        onProgress(p)

        p = p.copy(pingMs = ping())
        p = p.copy(phase = TestPhase.DOWNLOAD)
        onProgress(p)
        val down = measure({ counter, stop -> download(counter, stop) }) { onProgress(p.copy(currentBps = it)) }

        p = p.copy(downBps = down, phase = TestPhase.UPLOAD, currentBps = 0.0)
        onProgress(p)
        val up = measure({ counter, stop -> upload(counter, stop) }) { onProgress(p.copy(currentBps = it)) }

        p = p.copy(upBps = up, phase = TestPhase.DONE, currentBps = 0.0)
        onProgress(p)
        p
    }

    private fun open(path: String): HttpURLConnection =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
        }

    /** 빈 응답을 여러 번 받아 가장 짧은 왕복 시간(ms). 첫 요청은 연결 수립이 섞여서 버린다. */
    private fun ping(): Double {
        var best = Double.MAX_VALUE
        repeat(6) { i ->
            val start = System.nanoTime()
            val conn = open("/__down?bytes=0")
            if (conn.responseCode != 200) throw IOException("서버 응답 ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
            val ms = (System.nanoTime() - start) / 1_000_000.0
            if (i > 0 && ms < best) best = ms
        }
        return best
    }

    /**
     * worker를 여러 개 돌리며 주고받은 바이트를 세고, 워밍업 뒤 구간의 평균 속도를 돌려준다.
     * 모든 연결이 실패하면 예외를 던진다.
     */
    private suspend fun measure(
        worker: (counter: AtomicLong, stop: () -> Boolean) -> Unit,
        onSpeed: (Double) -> Unit,
    ): Double = coroutineScope {
        val counter = AtomicLong()
        val failures = AtomicLong()
        var firstError: Throwable? = null
        val start = System.nanoTime()
        val deadline = start + (WARMUP_MS + MEASURE_MS) * 1_000_000
        val jobs = List(CONNECTIONS) {
            launch {
                val stop = { !isActive || System.nanoTime() >= deadline }
                try {
                    worker(counter, stop)
                } catch (e: IOException) {
                    // 시간이 다 돼 끊은 것이 아니면 실패로 센다
                    if (!stop()) {
                        firstError = e
                        failures.incrementAndGet()
                    }
                }
            }
        }

        var baseBytes = 0L
        var baseTime = 0L
        var speed = 0.0
        while (System.nanoTime() < deadline && jobs.any { it.isActive }) {
            delay(200)
            val now = System.nanoTime()
            val bytes = counter.get()
            if (baseTime == 0L) {
                if (now - start >= WARMUP_MS * 1_000_000) {
                    baseBytes = bytes
                    baseTime = now
                } else {
                    // 워밍업 중에는 시작부터의 평균을 임시로 보여 준다
                    onSpeed(bytes / ((now - start) / 1e9))
                }
            } else {
                speed = (bytes - baseBytes) / ((now - baseTime) / 1e9)
                onSpeed(speed)
            }
        }
        if (failures.get() >= CONNECTIONS) throw IOException(firstError?.message ?: "연결 실패")
        speed
    }

    private fun download(counter: AtomicLong, stop: () -> Boolean) {
        val buffer = ByteArray(64 * 1024)
        while (!stop()) {
            val conn = open("/__down?bytes=$DOWN_CHUNK")
            try {
                if (conn.responseCode != 200) throw IOException("서버 응답 ${conn.responseCode}")
                conn.inputStream.use { input ->
                    while (!stop()) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        counter.addAndGet(n.toLong())
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun upload(counter: AtomicLong, stop: () -> Boolean) {
        val buffer = Random.nextBytes(64 * 1024)
        while (!stop()) {
            val conn = open("/__up")
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(UP_CHUNK)
                var sent = 0
                conn.outputStream.use { output ->
                    while (sent < UP_CHUNK) {
                        // 시간이 다 되면 보내던 요청을 그대로 끊는다
                        if (stop()) return
                        val n = minOf(buffer.size, UP_CHUNK - sent)
                        output.write(buffer, 0, n)
                        sent += n
                        counter.addAndGet(n.toLong())
                    }
                }
                if (conn.responseCode !in 200..299) throw IOException("서버 응답 ${conn.responseCode}")
            } finally {
                conn.disconnect()
            }
        }
    }
}
