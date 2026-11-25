package com.shanshui.performance.anr

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 覆盖系统错误补查及通用分区的独立降级。 */
class AnrEvidenceTest {
    /** 已匹配的系统说明经脱敏保存，不改变原判断来源。 */
    @Test
    fun preservesMatchedSystemInfoAndRedactsSecrets() {
        /** 直接命中时不需要重新读取系统错误列表。 */
        val lookup = AnrInfoLookup({ error("unexpected read") }, 11, 22, { true }, clock = { 123L })
        val result = lookup.collect("capture", "token=secret-value", false, Long.MAX_VALUE)
        assertEquals("ok", result.status)
        assertEquals(123L, result.capturedAt)
        assertEquals("token=<redacted>", result.text)
    }

    /** 后台补查等待记录出现，只接受同 UID、PID 和状态。 */
    @Test
    fun findsDelayedMatchingInfoWithoutUsingOtherProcesses() {
        /** 虚拟时间避免测试等待真实系统时钟。 */
        var now = 0L
        var reads = 0
        val lookup = AnrInfoLookup(
            read = {
                reads++
                listOf(AnrErrorState(99, 22, true, "other uid"), AnrErrorState(11, 99, true, "other pid"),
                    AnrErrorState(11, 22, false, "not an anr")) +
                    if (reads >= 3) listOf(AnrErrorState(11, 22, true, "matched")) else emptyList()
            }, uid = 11, pid = 22, unambiguous = { true }, clock = { 123L }, nanoTime = { now },
            sleep = { now += TimeUnit.MILLISECONDS.toNanos(it) },
        )
        val result = lookup.collect("capture", null, true, TimeUnit.SECONDS.toNanos(2))
        assertEquals("matched", result.text)
        assertEquals(3, reads)
    }

    /** 空值、权限失败和并发歧义分别返回不可用原因。 */
    @Test
    fun rejectsMissingPermissionAndAmbiguousEvidence() {
        var now = 0L
        val missing = AnrInfoLookup({ listOf(AnrErrorState(11, 22, true, "")) }, 11, 22, { true },
            nanoTime = { now }, sleep = { now += TimeUnit.MILLISECONDS.toNanos(it) })
        assertEquals("not_found", missing.collect("capture", null, true, TimeUnit.MILLISECONDS.toNanos(500)).reason)
        val denied = AnrInfoLookup({ throw SecurityException() }, 11, 22, { true })
        assertEquals("permission_denied", denied.collect("capture", null, true, Long.MAX_VALUE).reason)
        val ambiguous = AnrInfoLookup({ emptyList() }, 11, 22, { false })
        val result = ambiguous.collect("capture", "untrusted", false, Long.MAX_VALUE)
        assertEquals("ambiguous", result.reason)
        assertNull(result.text)
        val duplicate = AnrInfoLookup({ listOf(AnrErrorState(11, 22, true, "a"),
            AnrErrorState(11, 22, true, "b")) }, 11, 22, { true })
        assertEquals("ambiguous", duplicate.collect("capture", null, true, Long.MAX_VALUE).reason)
        /** 查询期间出现重叠采集时，已读到的说明也不能关联。 */
        var clear = true
        val overlap = AnrInfoLookup({ clear = false; listOf(AnrErrorState(11, 22, true, "stale")) }, 11, 22, { clear })
        assertEquals("ambiguous", overlap.collect("capture", null, true, Long.MAX_VALUE).reason)
    }

}
