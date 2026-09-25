package com.sack.pcremote.session

import com.sack.pcremote.net.GpuStats
import com.sack.pcremote.net.NetStats
import com.sack.pcremote.net.SystemStats
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricHistoryTest {
    @Test fun `keeps the last two minutes only`() {
        var h = MetricHistory()
        repeat(MetricHistory.MAX + 30) { h = h.add(SystemStats(cpu = it.toDouble())) }
        assertEquals(MetricHistory.MAX, h.cpu.size)
        assertEquals((MetricHistory.MAX + 29).toFloat(), h.cpu.last())
    }

    @Test fun `missing gpu and network count as zero`() {
        val h = MetricHistory().add(SystemStats(cpu = 10.0)).add(SystemStats(gpu = GpuStats(usage = 40.0), net = NetStats(rxBps = 1000)))
        assertEquals(listOf(0f, 40f), h.gpu)
        assertEquals(listOf(0f, 1000f), h.rx)
    }
}
