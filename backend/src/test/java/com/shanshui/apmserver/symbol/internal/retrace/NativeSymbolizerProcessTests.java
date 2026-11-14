package com.shanshui.apmserver.symbol.internal.retrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 用受控子进程验证退出、预算、超时及许可回收。 */
class NativeSymbolizerProcessTests {
    /** 独立临时目录。 */
    @TempDir Path root;

    /** 正常处理保留完整输出；启动失败和异常退出不泄露标准错误。 */
    @Test
    void handlesSuccessAndUnavailableOrFailedTool() throws Exception {
        var runner = new NativeSymbolizerProcess();
        var echo = script("exec cat");
        var ok = runner.run(echo, root, List.of(), "0x123\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2));
        assertTrue(ok.successful());
        assertEquals("0x123\n", new String(ok.output(), StandardCharsets.UTF_8));
        assertEquals("native_tool_unavailable", runner.run(root.resolve("missing"), root, List.of(), new byte[0], Duration.ofSeconds(1)).reason());
        var failed = runner.run(script("echo secret >&2; exit 7"), root, List.of(), new byte[0], Duration.ofSeconds(2));
        assertEquals("native_tool_failed", failed.reason());
        assertNull(failed.output());
        assertEquals("native_tool_failed", runner.run(script("exit 137"), root, List.of(), new byte[0], Duration.ofSeconds(2)).reason());
        assertEquals("native_tool_failed", runner.run(script("kill -XCPU $$"), root, List.of(), new byte[0], Duration.ofSeconds(2)).reason());
    }

    /** 原始标准错误即使包含敏感文本也不进入日志和返回值。 */
    @Test
    void neverLogsRawStderr() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NativeSymbolizerProcess.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var result = new NativeSymbolizerProcess().run(script("echo 'sensitive-stderr-marker' >&2; exit 7"),
                    root, List.of(), new byte[0], Duration.ofSeconds(3));
            assertNull(result.output());
            assertEquals("native_tool_failed", result.reason());
            assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains("sensitive-stderr-marker")));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    /** 版本、启动器和隔离故障均是可解释的安全延期。 */
    @Test
    void probesThePinnedVersionAndDefersUnavailableTools() throws Exception {
        var runner = new NativeSymbolizerProcess();
        assertTrue(runner.probe(script("echo 'Ubuntu LLVM version 19.1.7'"), root, Duration.ofSeconds(2)).successful());
        assertEquals("native_tool_version_unsupported", runner.probe(script("echo 'LLVM version 20.0.0'"), root, Duration.ofSeconds(2)).reason());
        assertEquals("native_tool_unavailable", runner.probe(root.resolve("absent"), root, Duration.ofSeconds(2)).reason());
        assertEquals("native_sandbox_unavailable", runner.probe(script("exit 125"), root, Duration.ofSeconds(2)).reason());
        assertEquals("native_tool_unavailable", runner.probe(script("exit 127"), root, Duration.ofSeconds(2)).reason());
    }

    /** 任一管道超出独立预算时终止进程并返回安全分类。 */
    @Test
    void boundsBothOutputStreams() throws Exception {
        var runner = new NativeSymbolizerProcess();
        var stdout = runner.run(script("exec head -c 600000 /dev/zero"), root, List.of(), new byte[0], Duration.ofSeconds(3));
        assertEquals("native_tool_output_limit", stdout.reason());
        var stderr = runner.run(script("exec head -c 20000 /dev/zero >&2"), root, List.of(), new byte[0], Duration.ofSeconds(3));
        assertEquals("native_tool_output_limit", stderr.reason());
        assertNull(stderr.output());
    }

    /** 超时和并发占用后，只有旧进程退出才可以启动下一次。 */
    @Test
    void refusesConcurrentWorkAndReleasesPermitAfterTimeout() throws Exception {
        var runner = new NativeSymbolizerProcess();
        var marker = root.resolve("entered");
        var slow = script("echo $$ > '" + marker + "'; exec sleep 30");
        try (var workers = Executors.newSingleThreadExecutor()) {
            // 留出独立的启动窗口，避免机器负载较高时在进程写入标记前耗尽测试期限。
            var first = workers.submit(() -> runner.run(slow, root, List.of(), new byte[0], Duration.ofSeconds(5)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(Files.exists(marker));
            assertEquals("native_tool_busy", runner.run(slow, root, List.of(), new byte[0], Duration.ofSeconds(1)).reason());
            assertEquals("native_tool_timeout", first.get(7, TimeUnit.SECONDS).reason());
            long pid = Long.parseLong(Files.readString(marker).trim());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(runner.run(script("exec cat"), root, List.of(), new byte[0], Duration.ofSeconds(2)).successful());
        }
    }

    /** 中断必须停止进程、恢复线程中断标志并释放许可。 */
    @Test
    void interruptsAndReapsRunningProcess() throws Exception {
        var runner = new NativeSymbolizerProcess();
        var marker = root.resolve("interrupted-entered");
        var slow = script("echo ready > '" + marker + "'; exec sleep 10");
        var reason = new java.util.concurrent.atomic.AtomicReference<String>();
        var retained = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = Thread.ofPlatform().start(() -> {
            reason.set(runner.run(slow, root, List.of(), new byte[0], Duration.ofSeconds(10)).reason());
            retained.set(Thread.currentThread().isInterrupted());
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(Files.exists(marker));
        thread.interrupt();
        thread.join(3000);
        assertFalse(thread.isAlive());
        assertEquals("native_tool_interrupted", reason.get());
        assertTrue(retained.get());
        assertTrue(runner.run(script("exec cat"), root, List.of(), new byte[0], Duration.ofSeconds(2)).successful());
    }

    /** 子进程继承输出管道时，父进程退出也不能突破调用期限。 */
    @Test
    void boundsInheritedPipesAfterParentExit() throws Exception {
        var runner = new NativeSymbolizerProcess();
        var parent = script("sleep 3 & exit 0");
        long start = System.nanoTime();
        var result = runner.run(parent, root, List.of(), new byte[0], Duration.ofMillis(250));
        assertEquals("native_tool_timeout", result.reason());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
    }

    /** 在隔离目录创建单个可执行测试脚本。 */
    private Path script(String body) throws Exception {
        Path path = Files.createTempFile(root, "process-", ".sh");
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        assertTrue(path.toFile().setExecutable(true));
        return path.toAbsolutePath();
    }
}
