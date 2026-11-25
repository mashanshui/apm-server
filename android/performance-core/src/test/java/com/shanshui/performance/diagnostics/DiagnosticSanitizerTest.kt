package com.shanshui.performance.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证有界脱敏不会丢失本地裁剪状态。 */
class DiagnosticSanitizerTest {
    /** 未裁剪文本继续沿用 Crash 的脱敏结果。 */
    @Test
    fun preservesRedactionWithoutReportingTruncation() {
        /** 输入中的凭据应在保存前被掩盖。 */
        val result = DiagnosticSanitizer.sanitizeWithStatus("token=abcd", 20)
        assertEquals("token=<redacted>", result.value)
        assertFalse(result.truncated)
        assertEquals(result.value, DiagnosticSanitizer.sanitize("token=abcd", 20))
    }

    /** 脱敏后输出超限与输入预裁剪均必须标记。 */
    @Test
    fun recordsBothOutputAndInputTruncation() {
        /** 脱敏占位符比原凭据更长时也可能发生输出裁剪。 */
        val outputLimited = DiagnosticSanitizer.sanitizeWithStatus("token=abcd", 8)
        assertTrue(outputLimited.truncated)
        assertFalse(outputLimited.value.contains("abcd"))
        assertEquals(8, outputLimited.value.length)

        /** 极长输入先被有界读取，同样要保留裁剪状态。 */
        val inputLimited = DiagnosticSanitizer.sanitizeWithStatus("a".repeat(41), 10)
        assertTrue(inputLimited.truncated)
        assertEquals("a".repeat(10), inputLimited.value)
    }
}
