package com.shanshui.apmserver.symbol.internal.retrace;

import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

/** 验证固定文件批量调用及安全失败，不实际执行 ELF。 */
class NativeSymbolizerTests {
    /** 受控临时文件卷。 */
    @TempDir Path root;

    /** 相同地址去重后仅通过标准输入传递，命令行包含固定来源和安全选项。 */
    @Test
    void submitsCanonicalDeduplicatedAddressesWithoutShell() throws Exception {
        var properties = new SymbolProperties();
        properties.setDirectory(root.toString());
        properties.setNativeLauncherPath(root.resolve("launcher").toString());
        var process = mock(NativeSymbolizerProcess.class);
        var lease = mock(SymbolFileLease.class);
        var object = Files.writeString(root.resolve("fixed.so"), "fixture").toRealPath();
        when(lease.path()).thenReturn(object);
        when(process.probe(any(), any(), any())).thenReturn(new NativeSymbolizerProcess.Result(new byte[0], null));
        String json = "{\"Address\":\"0xffffffffffffffff\",\"Symbol\":[{\"FunctionName\":\"f\",\"FileName\":\"/private/a.cc\",\"Line\":0}]}\n";
        when(process.run(any(), any(), anyList(), any(), any())).thenReturn(new NativeSymbolizerProcess.Result(json.getBytes(StandardCharsets.UTF_8), null));
        var result = new NativeSymbolizer(properties, process, new ObjectMapper()).symbolize(lease,
                List.of("0XFFFFFFFFFFFFFFFF", "0xffffffffffffffff"), Duration.ofSeconds(2));
        assertNull(result.reason());
        assertEquals("a.cc", result.frames().get("0xffffffffffffffff").inline().get(0).file());
        verify(process).run(eq(root.resolve("launcher")), eq(root.toRealPath()),
                eq(List.of("--output-style=JSON", "--inlines", "--demangle", "--no-debuginfod", "--obj=" + object)),
                aryEq("0xffffffffffffffff\n".getBytes(StandardCharsets.US_ASCII)), any());
    }

    /** 工具不可用、无效地址和越界来源均不启动实际解析。 */
    @Test
    void defersUnavailableAndRejectsUntrustedPaths() throws Exception {
        var properties = new SymbolProperties();
        properties.setDirectory(root.toString());
        properties.setNativeLauncherPath(root.resolve("launcher").toString());
        var process = mock(NativeSymbolizerProcess.class);
        var lease = mock(SymbolFileLease.class);
        var object = Files.writeString(root.resolve("fixed.so"), "fixture");
        when(lease.path()).thenReturn(object);
        when(process.probe(any(), any(), any())).thenReturn(new NativeSymbolizerProcess.Result(null, "native_tool_unavailable"));
        var symbolizer = new NativeSymbolizer(properties, process, new ObjectMapper());
        assertEquals("native_tool_unavailable", symbolizer.symbolize(lease, List.of("0x1"), Duration.ofSeconds(1)).reason());
        assertEquals("native_invalid_output_or_request", symbolizer.symbolize(lease, List.of("0x10000000000000000"), Duration.ofSeconds(1)).reason());
        var outside = Files.createTempFile("native-symbol-outside", ".so");
        try {
            when(lease.path()).thenReturn(outside);
            assertEquals("native_tool_unavailable", symbolizer.symbolize(lease, List.of("0x1"), Duration.ofSeconds(1)).reason());
        } finally { Files.deleteIfExists(outside); }
        verify(process, never()).run(any(), any(), anyList(), any(), any());
    }

    /** 超量地址在构造完整标准输入前被拒绝，不调用版本探测或工具。 */
    @Test
    void rejectsExcessiveInputBeforeStartingTheTool() {
        var properties = new SymbolProperties();
        var process = mock(NativeSymbolizerProcess.class);
        var symbolizer = new NativeSymbolizer(properties, process, new ObjectMapper());
        var addresses = java.util.stream.LongStream.range(0, 100_000)
                .mapToObj(value -> "0x" + Long.toHexString(value)).toList();
        var result = symbolizer.symbolize(mock(SymbolFileLease.class), addresses, Duration.ofSeconds(2));
        assertEquals("native_input_limit", result.reason());
        verifyNoInteractions(process);
    }
}
