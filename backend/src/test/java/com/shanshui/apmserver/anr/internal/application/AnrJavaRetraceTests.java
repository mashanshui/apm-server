package com.shanshui.apmserver.anr.internal.application;

import com.shanshui.apmserver.crash.api.DiagnosticSupport;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 R8 和受控失败验证 ANR Java 帧适配。 */
class AnrJavaRetraceTests {
    /** 隔离 mapping 文件目录。 */
    @TempDir Path directory;

    /** 不带 at 的主栈和多个 ART 线程保留内联、多候选与锁位置。 */
    @Test
    void retracesRealR8FramesWithoutMovingLocksOrNativeLines() throws Exception {
        Path mapping = Files.writeString(directory.resolve("mapping.txt"), """
                com.example.Main -> a:
                    1:1:void inner():10:10 -> b
                    1:1:void outer():20:20 -> b
                com.example.Candidates -> e:
                    void first(int) -> f
                    void second(java.lang.String) -> f
                com.example.Worker -> c:
                    void run() -> d
                """);
        var engine = new R8RetraceEngine();
        engine.validate(mapping);
        var symbols = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        when(symbols.retraceAuxiliary(any(), anyList(), anyLong(), any())).thenAnswer(invocation -> {
            List<String> lines = invocation.getArgument(1);
            long maxBytes = invocation.getArgument(2);
            return engine.retrace(mapping, lines, maxBytes);
        });
        var retrace = new AnrJavaRetrace(symbols, identitySanitizer());
        String main = "a.b(SourceFile:1)\ne.f(Unknown Source)\n";
        String trace = "\"main\" prio=5 tid=1 Blocked\n"
                + "  at a.b(SourceFile:1)\n"
                + "  - waiting to lock <0x1> (a java.lang.Object) held by thread 2\n"
                + "  at e.f(Unknown Source)\n"
                + "  native: #00 pc 00000000 /system/lib64/libc.so\n\n"
                + "\"worker\" prio=5 tid=2 Runnable\n"
                + "  at c.d(Unknown Source)\n"
                + "  at a.b(";
        var result = retrace.resolve(main, trace, true, lease);
        assertThat(result.mainThread().status()).isEqualTo("symbolicated");
        assertThat(result.mainThread().text()).contains("Main.inner", "Main.outer", "Candidates.first", "Candidates.second");
        assertThat(result.trace().status()).isEqualTo("partial");
        String output = result.trace().text();
        assertThat(output).contains("Main.inner", "Main.outer", "Candidates.first", "Candidates.second", "Worker.run",
                "  - waiting to lock <0x1> (a java.lang.Object) held by thread 2",
                "  native: #00 pc 00000000 /system/lib64/libc.so", "  at a.b(");
        assertThat(output.indexOf("Main.outer")).isLessThan(output.indexOf("  - waiting to lock"));
        assertThat(output.indexOf("  - waiting to lock")).isLessThan(output.indexOf("Candidates.first"));
        assertThat(output.indexOf("native: #00")).isLessThan(output.indexOf("\"worker\""));
        assertThat(output.indexOf("\"worker\"")).isLessThan(output.indexOf("Worker.run"));
    }

    /** 一个分区失败不阻止另一分区的可用结果。 */
    @Test
    void reportsIndependentFailuresAndKeepsUnrecognizedText() {
        var symbols = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        when(symbols.retraceAuxiliary(any(), anyList(), anyLong(), any()))
                .thenReturn(SymbolicationResult.failed("parser_busy"))
                .thenReturn(new SymbolicationResult("    at com.example.Real.run(Real.java:12)",
                        SymbolicationResult.Status.SYMBOLICATED, null));
        var retrace = new AnrJavaRetrace(symbols, identitySanitizer());
        var result = retrace.resolve("a.b(SourceFile:1)",
                "\"main\" prio=5 tid=1 Runnable\n  at a.b(SourceFile:1)\n  unknown text\n", false, lease);
        assertThat(result.mainThread().status()).isEqualTo("failed");
        assertThat(result.mainThread().reason()).isEqualTo("parser_busy");
        assertThat(result.mainThread().text()).isNull();
        assertThat(result.trace().status()).isEqualTo("symbolicated");
        assertThat(result.trace().text()).contains("com.example.Real.run", "  unknown text");
    }

    /** 无变化、超限和不完整尾行保留原始内容。 */
    @Test
    void boundsOutputAndDoesNotLabelUnchangedFramesAsResolved() {
        var symbols = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        when(symbols.retraceAuxiliary(any(), anyList(), anyLong(), any()))
                .thenAnswer(invocation -> new SymbolicationResult(String.join("\n", invocation.<List<String>>getArgument(1)),
                        SymbolicationResult.Status.SYMBOLICATED, null));
        var retrace = new AnrJavaRetrace(symbols, identitySanitizer());
        var unchanged = retrace.resolve("a.b(SourceFile:1)", "unknown native content", false, lease);
        assertThat(unchanged.mainThread().status()).isEqualTo("not_applicable");
        assertThat(unchanged.trace().status()).isEqualTo("not_applicable");
        when(symbols.retraceAuxiliary(any(), anyList(), anyLong(), any()))
                .thenReturn(new SymbolicationResult("x".repeat(33 * 1024), SymbolicationResult.Status.SYMBOLICATED, null));
        var oversized = retrace.resolve("a.b(SourceFile:1)", null, false, lease);
        assertThat(oversized.mainThread().status()).isEqualTo("failed");
        assertThat(oversized.mainThread().reason()).isEqualTo("output_limit");
        assertThat(oversized.mainThread().text()).isNull();
    }

    /** 超过单线程帧预算时发布已完成片段，其余帧保持原样。 */
    @Test
    void retainsFramesBeyondThreadBudgetAndIgnoresUnboundTrace() {
        var symbols = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        when(symbols.retraceAuxiliary(any(), anyList(), anyLong(), any())).thenAnswer(invocation -> {
            List<String> frames = invocation.getArgument(1);
            return new SymbolicationResult(String.join("\n", frames.stream()
                    .map(frame -> frame.replace("a.b", "com.example.Resolved.run")).toList()),
                    SymbolicationResult.Status.SYMBOLICATED, null);
        });
        var retrace = new AnrJavaRetrace(symbols, identitySanitizer());
        String trace = "\"main\" prio=5 tid=1 Runnable\n" + "  at a.b(SourceFile:1)\n".repeat(300);
        var result = retrace.resolve(null, trace, false, lease);
        assertThat(result.trace().status()).isEqualTo("partial");
        assertThat(result.trace().reason()).isEqualTo("budget_exhausted");
        assertThat(result.trace().text()).contains("com.example.Resolved.run", "  at a.b(SourceFile:1)");
        var unbound = retrace.resolve(null, "  at a.b(SourceFile:1)\n", false, lease);
        assertThat(unbound.trace().status()).isEqualTo("not_applicable");
        assertThat(unbound.trace().text()).isNull();
    }

    /** 多线程候选膨胀受整份结果字节预算限制。 */
    @Test
    void limitsCombinedOutputAcrossThreads() {
        var symbols = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        String expanded = "    at com.example.Candidate.run(Candidate.java:12)\n".repeat(600);
        when(symbols.retraceAuxiliary(any(), anyList(), anyLong(), any()))
                .thenReturn(new SymbolicationResult(expanded, SymbolicationResult.Status.SYMBOLICATED, null));
        var retrace = new AnrJavaRetrace(symbols, identitySanitizer());
        StringBuilder trace = new StringBuilder();
        for (int index = 0; index < 12; index++) {
            trace.append('"').append("worker-").append(index).append("\" prio=5 tid=").append(index + 1)
                    .append(" Runnable\n  at a.b(SourceFile:1)\n\n");
        }
        var result = retrace.resolve(null, trace.toString(), false, lease);
        assertThat(result.trace().status()).isEqualTo("partial");
        assertThat(result.trace().reason()).isEqualTo("output_limit");
        assertThat(result.trace().text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256 * 1024);
        assertThat(result.trace().text()).contains("com.example.Candidate.run", "  at a.b(SourceFile:1)");
    }

    /** 仅用于检查帧位置，不额外变更测试文本。 */
    private DiagnosticSupport identitySanitizer() {
        var sanitizer = mock(DiagnosticSupport.class);
        when(sanitizer.sanitizeText(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        return sanitizer;
    }
}
