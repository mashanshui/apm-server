package com.shanshui.apmserver.symbol.internal.retrace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通过真实 NDK 夹具和 Linux LLVM 核验四 ABI。 */
class NativeElfFixtureIntegrationTests {
    /** 独立受控符号目录和测试启动器。 */
    @TempDir Path root;

    /** 环境未显式提供时跳过，不能将跳过计作真实 ELF 验收通过。 */
    @Test
    void verifiesFourAbisAgainstRealLlvm() throws Exception {
        /** 夹具、NDK 和隔离镜像均由调用者显式指定。 */
        String fixtures = System.getenv("APM_ELF_FIXTURES");
        String image = System.getenv("APM_SYMBOLIZER_IMAGE");
        String ndk = System.getenv("ANDROID_NDK_HOME");
        Assumptions.assumeTrue(fixtures != null && image != null && ndk != null,
                "真实 ELF 测试需要 APM_ELF_FIXTURES、APM_SYMBOLIZER_IMAGE 和 ANDROID_NDK_HOME");
        /** 有实际 AGP 产物时追加四 ABI 专项；未提供时只验证可复现夹具。 */
        var arguments = new java.util.ArrayList<>(List.of("python3", "src/test/fixtures/elf/verify.py", fixtures, image));
        /** 可选的原始 Android 构建产物。 */
        String agpZip = System.getenv("APM_AGP_NATIVE_ZIP");
        if (agpZip != null && !agpZip.isBlank()) arguments.add(agpZip);
        var process = new ProcessBuilder(arguments)
                .redirectErrorStream(true).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            fail("真实 ELF 核验超时");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(4, output.lines().filter(line -> line.contains("通过")).count(), output);
        if (agpZip != null && !agpZip.isBlank()) assertTrue(output.contains("实际 AGP 符号包：四 ABI 身份和函数已验证"), output);
    }

    /** 真实 Linux 工具的逐行 JSON 能通过 Java 批量调用完整解析。 */
    @Test
    void resolvesRealElfThroughJavaProcessAndParser() throws Exception {
        String fixtures = System.getenv("APM_ELF_FIXTURES");
        String image = System.getenv("APM_SYMBOLIZER_IMAGE");
        String ndk = System.getenv("ANDROID_NDK_HOME");
        Assumptions.assumeTrue(fixtures != null && image != null && ndk != null,
                "真实 ELF 测试需要 APM_ELF_FIXTURES、APM_SYMBOLIZER_IMAGE 和 ANDROID_NDK_HOME");
        /** 从实际 NDK 符号表读取目标地址，不硬编码工具链生成的偏移。 */
        Path object = Files.copy(Path.of(fixtures, "arm64-v8a", "libapm_fixture.so"), root.resolve("fixture.so"));
        String host = System.getProperty("os.name").startsWith("Mac") ? "darwin-x86_64" : "linux-x86_64";
        Path nm = Path.of(ndk, "toolchains", "llvm", "prebuilt", host, "bin", "llvm-nm");
        var nmProcess = new ProcessBuilder(nm.toString(), "-n", object.toString()).redirectErrorStream(true).start();
        assertTrue(nmProcess.waitFor(10, TimeUnit.SECONDS));
        String symbols = new String(nmProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, nmProcess.exitValue());
        String address = symbols.lines().map(String::trim).filter(line -> line.endsWith(" T apm_fixture_add"))
                .map(line -> "0x" + line.split("\\s+")[0].replaceFirst("^0+(?!$)", ""))
                .findFirst().orElseThrow();
        /** Docker 是隔离测试包装器；生产使用镜像内固定启动器。 */
        Path launcher = root.resolve("docker-launcher");
        Files.writeString(launcher, "#!/bin/sh\nexec docker run --rm -i "
                + "-v \"$APM_SYMBOL_DIRECTORY:$APM_SYMBOL_DIRECTORY:ro\" -e APM_SYMBOL_DIRECTORY "
                + "--entrypoint /usr/local/bin/apm-native-symbolizer \"$APM_SYMBOLIZER_IMAGE\" \"$@\"\n");
        assertTrue(launcher.toFile().setExecutable(true));
        var properties = new SymbolProperties();
        properties.setDirectory(root.toString());
        properties.setNativeLauncherPath(launcher.toString());
        var lease = mock(SymbolFileLease.class);
        when(lease.path()).thenReturn(object);
        var symbolizer = new NativeSymbolizer(properties, new NativeSymbolizerProcess(), new ObjectMapper());
        var result = symbolizer.symbolize(lease, List.of(address, address), Duration.ofSeconds(2));
        assertNull(result.reason(), result.reason());
        var frame = result.frames().get(NativeSymbolizerJson.canonicalAddress(address));
        assertNotNull(frame);
        assertTrue(frame.successful());
        assertEquals("apm_fixture_add", frame.inline().get(0).function());
        assertEquals("minimal.c", frame.inline().get(0).file());
        assertTrue(frame.inline().get(0).line() > 0);
        // 同一真实 ELF 再通过 Crash 批处理，验证地址去重不增加物理帧。
        var registry = mock(com.shanshui.apmserver.symbol.api.SymbolRegistry.class);
        var app = java.util.UUID.randomUUID();
        when(lease.symbolId()).thenReturn(java.util.UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(java.util.Optional.of(lease));
        when(registry.symbolizeNative(eq(lease), anyList(), any())).thenAnswer(call -> {
            var actual = symbolizer.symbolize(lease, call.getArgument(1), call.getArgument(2));
            var mapped = new java.util.LinkedHashMap<String, com.shanshui.apmserver.symbol.api.NativeSymbolicationResult.Frame>();
            actual.frames().forEach((key, value) -> mapped.put(key, new com.shanshui.apmserver.symbol.api.NativeSymbolicationResult.Frame(
                    value.inline().stream().map(item -> new com.shanshui.apmserver.symbol.api.NativeSymbolicationResult.Inline(item.function(), item.file(), item.line(), item.column())).toList(), value.reason())));
            return new com.shanshui.apmserver.symbol.api.NativeSymbolicationResult(mapped, actual.reason());
        });
        var pending = new com.shanshui.apmserver.crash.api.NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var physical = new com.shanshui.apmserver.crash.api.NativeStack.Frame(0, "m", address, null, null, pending);
        var module = new com.shanshui.apmserver.crash.api.NativeCrash.Module("m", "libapm_fixture.so", null, "arm64-v8a", "abcd");
        var batch = new com.shanshui.apmserver.crash.internal.application.NativeFrameBatch(registry)
                .resolve(app, "1", List.of(module), List.of(physical, physical), Duration.ofSeconds(2));
        assertEquals(2, batch.size());
        assertEquals("apm_fixture_add", batch.getFirst().resolution().inline().getFirst().function());
        assertEquals(address, batch.get(1).relativePc());
        verify(registry, times(1)).symbolizeNative(eq(lease), eq(List.of(address)), any());
    }

    /** 实际镜像中隔离不可用或必需库缺失均产生安全可解释的延期。 */
    @Test
    void defersWhenRealLinuxSandboxOrLibraryIsUnavailable() throws Exception {
        Assumptions.assumeTrue(System.getenv("APM_SYMBOLIZER_IMAGE") != null,
                "真实 Linux 故障测试需要 APM_SYMBOLIZER_IMAGE");
        /** 测试专用 seccomp 只拒绝创建 Landlock 规则，不改变正式部署配置。 */
        Path profile = root.resolve("deny-landlock.json");
        Files.writeString(profile, "{\"defaultAction\":\"SCMP_ACT_ALLOW\",\"syscalls\":[{\"names\":[\"landlock_create_ruleset\"],\"action\":\"SCMP_ACT_ERRNO\",\"errnoRet\":38}]}");
        Path blocked = root.resolve("blocked-launcher");
        Files.writeString(blocked, "#!/bin/sh\nexec docker run --rm -i --security-opt "
                + shellLiteral("seccomp=" + profile) + " -e APM_SYMBOL_DIRECTORY=/var/lib/apm/symbols "
                + "--entrypoint /usr/local/bin/apm-native-symbolizer \"$APM_SYMBOLIZER_IMAGE\" \"$@\"\n");
        assertTrue(blocked.toFile().setExecutable(true));
        var process = new NativeSymbolizerProcess();
        // 该专项通过 Docker 冷启动注入隔离故障，容器启动不属于生产工具执行预算。
        assertEquals("native_sandbox_unavailable", process.probe(blocked, root, Duration.ofSeconds(10)).reason());
        /** 覆盖动态库模拟损坏，验证强制依赖不能静默跳过。 */
        Path missing = root.resolve("missing-launcher");
        Files.writeString(missing, "#!/bin/sh\nexec docker run --rm -i -v /dev/null:/usr/local/lib/apm-native-symbolizer-sandbox.so:ro "
                + "-e APM_SYMBOL_DIRECTORY=/var/lib/apm/symbols --entrypoint /usr/local/bin/apm-native-symbolizer \"$APM_SYMBOLIZER_IMAGE\" \"$@\"\n");
        assertTrue(missing.toFile().setExecutable(true));
        assertEquals("native_tool_unavailable", process.probe(missing, root, Duration.ofSeconds(10)).reason());
    }

    /** 将测试生成的路径转换为安全的 shell 单词。 */
    private static String shellLiteral(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
