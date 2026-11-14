package com.shanshui.apmserver.symbol.internal.retrace;

import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 使用固定 SO 租约批量执行 Native 地址还原。 */
@Component
public class NativeSymbolizer {
    /** 安全日志不包含地址、文件路径或原始工具输出。 */
    private static final Logger log = LoggerFactory.getLogger(NativeSymbolizer.class);
    /** 单模块进程预算和标准输入字节上限。 */
    private static final Duration MODULE_LIMIT = Duration.ofSeconds(2);
    private static final int INPUT_LIMIT = 512 * 1024;
    /** 固定工具配置、进程门禁及 JSON 转换器。 */
    private final SymbolProperties properties;
    private final NativeSymbolizerProcess process;
    private final NativeSymbolizerJson parser;

    /** 绑定现有配置、受控执行器与 JSON 解析器。 */
    public NativeSymbolizer(SymbolProperties properties, NativeSymbolizerProcess process, ObjectMapper mapper) {
        this.properties = properties;
        this.process = process;
        this.parser = new NativeSymbolizerJson(mapper);
    }

    /** 单模块批次结果；失败原因不包含文件和现场内容。 */
    public record Result(Map<String, NativeSymbolizerJson.Frame> frames, String reason) { }

    /** 按固定租约去重地址并在共享期限内执行版本探测和批量还原。 */
    public Result symbolize(SymbolFileLease lease, List<String> addresses, Duration budget) {
        /** 所有阶段共享同一个开始时间，不能为版本探测后重置期限。 */
        long started = System.nanoTime();
        try {
            if (lease == null || addresses == null || budget == null || budget.isNegative() || budget.isZero()) return failure("native_invalid_request");
            /** 地址按完整无符号值去重，不对源端 PC 再做修正。 */
            var distinct = new LinkedHashSet<String>();
            /** 在构造完整输入前检查增量字节预算，避免超量请求先分配无限集合。 */
            int inputBytes = 0;
            for (String address : addresses) {
                String canonical = NativeSymbolizerJson.canonicalAddress(address);
                if (distinct.add(canonical)) {
                    inputBytes += canonical.length() + 1;
                    if (inputBytes > INPUT_LIMIT) return failure("native_input_limit");
                }
            }
            if (distinct.isEmpty()) return new Result(Map.of(), null);
            String text = String.join("\n", distinct) + "\n";
            byte[] input = text.getBytes(StandardCharsets.US_ASCII);
            /** 固定来源必须位于实际受控文件卷，不能由调用方提供任意路径。 */
            Path directory = Path.of(properties.getDirectory()).toRealPath();
            Path object = lease.path().toRealPath();
            if (!object.startsWith(directory)) return failure("native_tool_unavailable");
            String configured = properties.getNativeLauncherPath();
            if (configured == null || configured.isBlank()) return failure("native_tool_unavailable");
            Path launcher = Path.of(configured);
            Duration total = budget.compareTo(MODULE_LIMIT) < 0 ? budget : MODULE_LIMIT;
            var version = process.probe(launcher, directory, remaining(started, total));
            if (!version.successful()) return failure(version.reason());
            /** 命令行只包含服务端控制的开关和固定租约；地址只写入标准输入。 */
            var arguments = List.of("--output-style=JSON", "--inlines", "--demangle", "--no-debuginfod", "--obj=" + object);
            var output = process.run(launcher, directory, arguments, input, remaining(started, total));
            if (!output.successful()) return failure(output.reason());
            var frames = parser.parse(output.output(), List.copyOf(distinct));
            log.info("Native 地址批量还原完成：地址数={}，结果数={}", distinct.size(), frames.size());
            return new Result(frames, null);
        } catch (IOException failure) {
            return failure("native_tool_unavailable");
        } catch (RuntimeException failure) {
            return failure("native_invalid_output_or_request");
        }
    }

    /** 返回同一模块的剩余期限，超时不再启动进程。 */
    private static Duration remaining(long started, Duration total) {
        long nanos = total.toNanos() - (System.nanoTime() - started);
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    /** 仅记录安全的失败类别，不泄露原始堆栈或工具输出。 */
    private static Result failure(String reason) {
        log.warn("Native 地址批量还原延期或失败：原因={}", reason);
        return new Result(Map.of(), reason);
    }
}
