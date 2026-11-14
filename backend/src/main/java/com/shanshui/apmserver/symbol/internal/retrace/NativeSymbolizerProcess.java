package com.shanshui.apmserver.symbol.internal.retrace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单实例受控 LLVM 子进程；不保留或记录原始标准错误。 */
@Component
public class NativeSymbolizerProcess {
    /** 安全日志只包含阶段、原因、字节及耗时。 */
    private static final Logger log = LoggerFactory.getLogger(NativeSymbolizerProcess.class);
    /** 标准输出及标准错误的独立预算。 */
    private static final int STDOUT_LIMIT = 512 * 1024;
    private static final int STDERR_LIMIT = 16 * 1024;
    /** 不排队的单进程许可。 */
    private final Semaphore permit = new Semaphore(1);

    /** 一次受控调用结果；失败时不暴露子进程输出。 */
    public record Result(byte[] output, String reason) {
        /** 是否取得完整可解析输出。 */
        public boolean successful() { return reason == null; }
    }

    /** 在相同资源限制和隔离条件下核验固定工具版本，不满足时只返回延期原因。 */
    public Result probe(Path launcher, Path symbolDirectory, Duration timeout) {
        /** 版本核验也使用同一单进程许可与输出预算。 */
        Result result = run(launcher, symbolDirectory, List.of("--version"), new byte[0], timeout);
        if (!result.successful()) return result;
        /** 仅接受固定发行版本；不把任意可执行文件当作有效工具。 */
        String version = new String(result.output(), java.nio.charset.StandardCharsets.UTF_8);
        if (!version.matches("(?s).*\\bLLVM version 19\\.1\\.7(?:\\s|$).*")) return failed("native_tool_version_unsupported");
        log.info("Native 工具版本核验完成：版本=19.1.7");
        return result;
    }

    /** 启动固定路径的工具，输入输出有界；进程退出前不释放许可。 */
    public Result run(Path launcher, Path symbolDirectory, List<String> arguments, byte[] input, Duration timeout) {
        /** 当前调用起点和成功/失败分类。 */
        long start = System.nanoTime();
        if (!permit.tryAcquire()) return failed("native_tool_busy");
        Process process = null;
        boolean interrupted = false;
        try {
            if (launcher == null || !launcher.isAbsolute() || !Files.isRegularFile(launcher) || !Files.isExecutable(launcher)
                    || symbolDirectory == null || !symbolDirectory.isAbsolute()) {
                return failed("native_tool_unavailable");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) return failed("native_tool_timeout");
            /** 不经过 shell 的不可变参数数组。 */
            var command = new java.util.ArrayList<String>();
            command.add(launcher.toString());
            command.addAll(arguments);
            var builder = new ProcessBuilder(command);
            builder.environment().put("APM_SYMBOL_DIRECTORY", symbolDirectory.toString());
            builder.environment().remove("DEBUGINFOD_URLS");
            process = builder.start();
            /** 统一的超量标志，任一输出触限立即终止进程。 */
            var overflow = new AtomicBoolean();
            var running = process;
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    var stdout = CompletableFuture.supplyAsync(() -> readBounded(running.getInputStream(), STDOUT_LIMIT, true, overflow, running), workers);
                    var stderr = CompletableFuture.supplyAsync(() -> readBounded(running.getErrorStream(), STDERR_LIMIT, false, overflow, running), workers);
                    var writer = CompletableFuture.runAsync(() -> writeInput(running, input), workers);
                    if (!process.waitFor(remaining(start, timeout), TimeUnit.NANOSECONDS)) return failed("native_tool_timeout");
                    /** 正常退出后仍须在同一期限内回收所有通道。 */
                    writer.get(remaining(start, timeout), TimeUnit.NANOSECONDS);
                    byte[] output = stdout.get(remaining(start, timeout), TimeUnit.NANOSECONDS);
                    stderr.get(remaining(start, timeout), TimeUnit.NANOSECONDS);
                    if (overflow.get()) return failed("native_tool_output_limit");
                    // 启动器和动态加载器的明确退出码对应可重试的隔离或依赖故障。
                    if (process.exitValue() == 125) return failed("native_sandbox_unavailable");
                    if (process.exitValue() == 126 || process.exitValue() == 127) return failed("native_tool_unavailable");
                    if (process.exitValue() != 0) return failed("native_tool_failed");
                    log.info("Native 工具执行完成：输出字节={}，耗时毫秒={}", output.length,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    return new Result(output, null);
                } finally {
                    if (process.isAlive()) stopAndWait(process);
                    closeStreams(process);
                    workers.shutdownNow();
                }
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            return failed("native_tool_interrupted");
        } catch (IOException failure) {
            return failed("native_tool_unavailable");
        } catch (TimeoutException failure) {
            return failed("native_tool_timeout");
        } catch (ExecutionException | RuntimeException failure) {
            return failed("native_tool_failed");
        } finally {
            if (process != null && process.isAlive()) stopAndWait(process);
            if (process != null) closeStreams(process);
            permit.release();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 返回可安全记录的失败分类，不包含原始工具输出。 */
    private static Result failed(String reason) {
        log.warn("Native 工具执行延期或失败：原因={}", reason);
        return new Result(null, reason);
    }

    /** 返回整个调用期限内剩余的纳秒数。 */
    private static long remaining(long start, Duration timeout) throws TimeoutException {
        long value = timeout.toNanos() - (System.nanoTime() - start);
        if (value <= 0) throw new TimeoutException();
        return value;
    }

    /** 有界并发消费一个输出流；标准错误只计数，不保存内容。 */
    private static byte[] readBounded(InputStream stream, int limit, boolean retain, AtomicBoolean overflow, Process process) {
        /** 输出缓冲及累计字节只覆盖当前进程。 */
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            for (int count; (count = stream.read(buffer)) != -1;) {
                total += count;
                if (total > limit) {
                    overflow.set(true);
                    process.destroyForcibly();
                    break;
                }
                if (retain) output.write(buffer, 0, count);
            }
        } catch (IOException ignored) {
            // 超时和强制终止会关闭管道；由进程状态决定安全失败分类。
        }
        return output.toByteArray();
    }

    /** 在独立线程写入地址并关闭标准输入，避免阻塞调用线程。 */
    private static void writeInput(Process process, byte[] input) {
        try (var stream = process.getOutputStream()) {
            stream.write(input);
        } catch (IOException ignored) {
            // 子进程提前退出时由退出码或超时统一分类。
        }
    }

    /** 强制结束后等待操作系统确认退出，保留调用线程的中断状态。 */
    private static void stopAndWait(Process process) {
        process.destroyForcibly();
        boolean interrupted = false;
        while (process.isAlive()) {
            try { process.waitFor(); }
            catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** 关闭进程管道以唤醒在途读取与写入。 */
    private static void closeStreams(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }
}
