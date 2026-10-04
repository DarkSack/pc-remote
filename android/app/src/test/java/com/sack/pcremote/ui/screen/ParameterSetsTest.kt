package com.sack.pcremote.ui.screen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParameterSetsTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun extractsSpsAndPpsWithStartCodes() {
        // 4-byte and 3-byte start codes mixed, as encoders write them.
        val au = b(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9, 9)
        val (sps, pps) = VideoDecoder.parameterSets(au)!!
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 1, 2), sps)
        assertArrayEquals(b(0, 0, 0, 1, 0x68, 3), pps)
    }

    @Test
    fun nullWithoutParameterSets() {
        assertNull(VideoDecoder.parameterSets(b(0, 0, 0, 1, 0x41, 5, 6)))
    }
}
