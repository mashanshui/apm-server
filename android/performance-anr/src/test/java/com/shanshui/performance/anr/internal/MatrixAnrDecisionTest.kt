package com.shanshui.performance.anr.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 Matrix 原有前后台阈值和错误状态轮询。 */
class MatrixAnrDecisionTest {
    /** 阈值本身不触发，超过阈值才触发。 */
    @Test
    fun keepsForegroundAndBackgroundThresholds() {
        assertFalse(MatrixAnrDecision.messageBlocked(-2_000, true))
        assertTrue(MatrixAnrDecision.messageBlocked(-2_001, true))
        assertFalse(MatrixAnrDecision.messageBlocked(-10_000, false))
        assertTrue(MatrixAnrDecision.messageBlocked(-10_001, false))
    }

    /** 最后一轮命中立即结束，始终保持 500ms 间隔。 */
    @Test
    fun pollsFortyTimesAndStopsOnMatch() {
        var reads = 0
        val waits = mutableListOf<Long>()
        val matched = MatrixAnrDecision.poll({ if (++reads == 40) "matched" else null }, { waits += it })
        assertEquals("matched", matched)
        assertEquals(40, reads)
        assertEquals(List(39) { 500L }, waits)
        reads = 0
        waits.clear()
        val absent = MatrixAnrDecision.poll<String>({ reads++; null }, { waits += it })
        assertNull(absent)
        assertEquals(40, reads)
        assertEquals(List(40) { 500L }, waits)
    }
}
