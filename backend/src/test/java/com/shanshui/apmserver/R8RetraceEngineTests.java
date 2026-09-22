package com.shanshui.apmserver;

import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证官方 R8 Retrace 对真实 release mapping 和非法文件的处理。 */
class R8RetraceEngineTests {

    /** 由外部测试命令注入的真实 mapping 路径。 */
    private static final String REAL_MAPPING_PROPERTY = "apm.real.mapping";

    /** 临时文件目录。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * 使用真实 R8 release mapping 验证完整读取、内联/多候选、无行号和异常安全输出。
     * 未提供外部样本时跳过，避免普通构建依赖工作区外的 Android 工程。
     */
    @Test
    void retracesRealReleaseMappingWhenConfigured() {
        Path mapping = configuredRealMapping();
        R8RetraceEngine engine = new R8RetraceEngine();

        engine.validate(mapping);

        SymbolicationResult candidateResult = engine.retrace(mapping,
                List.of("at l.d0.c(StandardMenuPopup.java:25)"), 1024 * 1024);
        assertEquals(SymbolicationResult.Status.SYMBOLICATED, candidateResult.status());
        assertTrue(candidateResult.text().contains("StandardMenuPopup.tryShow"));
        assertTrue(candidateResult.text().contains("StandardMenuPopup.show"));

        SymbolicationResult noLineResult = engine.retrace(mapping,
                List.of("at w1.w.<init>(PerformanceSdk.kt)"), 1024 * 1024);
        assertEquals(SymbolicationResult.Status.SYMBOLICATED, noLineResult.status());
        assertTrue(noLineResult.text().contains("com.example.nativelib.PerformanceSdk.<init>"));
    }

    /** 非 mapping 文本必须在上传校验阶段安全拒绝。 */
    @Test
    void rejectsInvalidMapping() throws Exception {
        Path invalid = Files.writeString(temporaryDirectory.resolve("invalid-mapping.txt"),
                "this is not a mapping");

        SymbolValidationException exception = assertThrows(SymbolValidationException.class,
                () -> new R8RetraceEngine().validate(invalid));

        assertEquals("INVALID_MAPPING", exception.getCode());
        assertEquals(422, exception.getStatus());
    }

    /** 还原输出达到上限时丢弃派生文本并返回安全降级原因。 */
    @Test
    void limitsRetraceOutput() throws Exception {
        Path mapping = Files.writeString(temporaryDirectory.resolve("small-mapping.txt"),
                "com.example.App -> a:\n    void run() -> b\n");
        R8RetraceEngine engine = new R8RetraceEngine();
        engine.validate(mapping);

        SymbolicationResult result = engine.retrace(mapping,
                List.of("at a.b(Unknown.java:1)"), 1);

        assertEquals(SymbolicationResult.Status.FAILED, result.status());
        assertEquals("output_limit", result.reason());
        assertTrue(result.text() == null || result.text().isEmpty());
    }

    /** mapping 文件不可读时还原只能返回降级结果，不能暴露底层路径。 */
    @Test
    void degradesWhenMappingCannotBeRead() {
        SymbolicationResult result = new R8RetraceEngine().retrace(
                temporaryDirectory.resolve("missing.mapping"), List.of("at a.b(Unknown.java:1)"), 1024);

        assertEquals(SymbolicationResult.Status.FAILED, result.status());
        assertEquals("retrace_failed", result.reason());
        assertTrue(result.text() == null);
    }

    /** 读取外部真实 mapping，并在不存在时跳过样本专项测试。 */
    private Path configuredRealMapping() {
        String configured = System.getProperty(REAL_MAPPING_PROPERTY);
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "未设置 -D" + REAL_MAPPING_PROPERTY + "，跳过外部 release mapping 测试");
        Path mapping = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(mapping), "真实 mapping 文件不存在: " + mapping);
        return mapping;
    }
}
