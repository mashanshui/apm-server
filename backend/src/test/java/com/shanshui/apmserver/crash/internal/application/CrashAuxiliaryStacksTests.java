package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证辅助栈各自状态、同源与完整输出预算。 */
class CrashAuxiliaryStacksTests {
    /** 当前 JSON 配置。 */ private final ObjectMapper mapper = new ObjectMapper();
    /** 模拟有界符号服务。 */ private final SymbolRegistry symbols = mock(SymbolRegistry.class);
    /** 同次主异常持有的固定 mapping。 */ private final SymbolFileLease mapping = mock(SymbolFileLease.class);
    /** 服务端文本脱敏。 */ private final CrashSanitizer sanitizer = new CrashSanitizer(new IngestConfigurationProperties());

    /** 一个线程成功、一个失败和一个空帧必须分别保留真实状态。 */
    @Test
    void keepsIndependentStatusesAndSameMappingSource() {
        UUID symbol = UUID.randomUUID();
        when(mapping.symbolId()).thenReturn(symbol);
        when(mapping.revision()).thenReturn(4);
        when(mapping.sha256()).thenReturn("a".repeat(64));
        when(symbols.retraceAuxiliary(eq(mapping), anyList(), eq(32768L), any()))
                .thenReturn(new SymbolicationResult("at A.one(A.java:1)\n at B.inline(B.java:2)", SymbolicationResult.Status.SYMBOLICATED, null))
                .thenReturn(SymbolicationResult.failed("parser_busy"));
        var resolver = new CrashAuxiliaryStacks(mapper, symbols, sanitizer);
        var output = mapper.readValue(resolver.resolve(payload(2, true), mapping), CrashThreadStack[].class);
        assertEquals("symbolicated", output[0].symbolicationStatus());
        assertEquals(symbol, output[0].symbolFileId());
        assertTrue(((String) output[0].stack().content()).contains("B.inline"));
        assertEquals("failed", output[1].symbolicationStatus());
        assertEquals("parser_busy", output[1].symbolicationReason());
        assertEquals("raw_frames", output[1].stack().format());
        assertEquals("not_applicable", output[2].symbolicationStatus());
        verify(symbols, times(2)).retraceAuxiliary(eq(mapping), anyList(), eq(32768L), any());
    }

    /** 已耗尽的总等待预算不启动后续线程还原。 */
    @Test
    void marksRemainingThreadsWhenBudgetIsExhausted() {
        var calls = new AtomicInteger();
        var resolver = new CrashAuxiliaryStacks(mapper, symbols, sanitizer,
                () -> calls.getAndIncrement() == 0 ? 0L : java.time.Duration.ofSeconds(3).toNanos());
        var output = mapper.readValue(resolver.resolve(payload(2, false), mapping), CrashThreadStack[].class);
        assertEquals("budget_exhausted", output[0].symbolicationReason());
        assertEquals("budget_exhausted", output[1].symbolicationReason());
        verifyNoInteractions(symbols);
    }

    /** 极端 JSON 转义不会突破总预算或截断成功结果。 */
    @Test
    void fallsBackToRawFramesWhenEscapingExceedsTotalBudget() {
        when(mapping.symbolId()).thenReturn(UUID.randomUUID());
        when(mapping.revision()).thenReturn(1);
        when(mapping.sha256()).thenReturn("a".repeat(64));
        String text = "\\\"".repeat(15000);
        when(symbols.retraceAuxiliary(eq(mapping), anyList(), eq(32768L), any()))
                .thenReturn(new SymbolicationResult(text, SymbolicationResult.Status.SYMBOLICATED, null));
        var resolver = new CrashAuxiliaryStacks(mapper, symbols, sanitizer);
        String output = resolver.resolve(payload(10, false), mapping);
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= 256 * 1024);
        var threads = mapper.readValue(output, CrashThreadStack[].class);
        assertTrue(List.of(threads).stream().anyMatch(thread -> "output_limit".equals(thread.symbolicationReason())));
        assertTrue(List.of(threads).stream().filter(thread -> "symbolicated".equals(thread.symbolicationStatus()))
                .allMatch(thread -> text.equals(thread.stack().content())));
    }

    /** 准备有帧线程及可选空帧线程的当前状态。 */
    private String payload(int count, boolean emptyThread) {
        List<CrashThreadStack> stacks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            stacks.add(new CrashThreadStack(Integer.toString(i), "pending", null,
                    new CrashThreadStack.Stack("raw_frames", List.of(new StackFrame("a.A", "a", "A.java", 1, false))),
                    null, null, null, null));
        }
        if (emptyThread) stacks.add(new CrashThreadStack(Integer.toString(count + 1), "not_applicable", null,
                new CrashThreadStack.Stack("raw_frames", List.of()), null, null, null, null));
        return mapper.writeValueAsString(stacks);
    }
}
