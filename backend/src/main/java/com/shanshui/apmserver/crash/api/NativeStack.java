package com.shanshui.apmserver.crash.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Native 现场及与原物理帧逐一对应的当前还原结果。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NativeStack(
        /** 进程 ABI。 */ String abi,
        /** 崩溃系统线程编号。 */ Integer systemTid,
        /** 已脱敏线程名称。 */ String threadName,
        /** 是否主线程。 */ Boolean mainThread,
        /** 原信号。 */ NativeCrash.Signal signal,
        /** 原寄存器。 */ NativeCrash.Registers registers,
        /** 主辅 Native 帧共用模块字典。 */ List<NativeCrash.Module> modules,
        /** 原物理主帧与还原结果。 */ Backtrace backtrace,
        /** 可靠关联的 Java 当前栈。 */ JavaStack javaStack) {

    /** 验证主栈、模块身份和原物理帧引用。 */
    public NativeStack {
        if (abi == null || !Set.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64").contains(abi)
                || modules == null || backtrace == null) throw new IllegalArgumentException("Native 栈缺少 ABI、模块或回溯");
        var ids = new java.util.HashSet<String>();
        for (var module : modules) {
            if (module == null || module.id() == null || !ids.add(module.id()) || !abi.equals(module.abi())) {
                throw new IllegalArgumentException("Native 模块身份无效");
            }
        }
        validateFrames(modules, backtrace);
        modules = List.copyOf(modules);
    }

    /** 主栈及辅助栈都必须引用同一模块字典，未知身份不能等待或声称成功。 */
    public static void validateFrames(List<NativeCrash.Module> modules, Backtrace backtrace) {
        var byId = modules.stream().collect(java.util.stream.Collectors.toMap(NativeCrash.Module::id, module -> module));
        for (var frame : backtrace.frames()) {
            var module = frame.moduleId() == null ? null : byId.get(frame.moduleId());
            if (frame.moduleId() != null && module == null) throw new IllegalArgumentException("Native 帧引用未知模块");
            boolean reliable = module != null && module.abi() != null && module.elfBuildId() != null
                    && module.elfBuildId().matches("(?:[a-f0-9]{2}){1,64}") && frame.relativePc() != null;
            if (!reliable && !"not_applicable".equals(frame.resolution().status())) {
                throw new IllegalArgumentException("缺少可靠模块身份的帧必须标记为不可处理");
            }
        }
    }

    /** 原采集回溯及当前有序物理帧，不用处理状态覆盖采集事实。 */
    public record Backtrace(
            /** 采集状态。 */ String status,
            /** 采集原因。 */ String reason,
            /** 采集是否截断。 */ Boolean truncated,
            /** 原采集时间。 */ Long capturedAt,
            /** 不新增或重排原物理帧。 */ List<Frame> frames) {
        /** 验证连续帧身份和必填结果。 */
        public Backtrace {
            if (frames == null) throw new IllegalArgumentException("Native 帧列表缺失");
            for (int index = 0; index < frames.size(); index++) {
                var frame = frames.get(index);
                if (frame == null || frame.index() == null || frame.index() != index || frame.resolution() == null) {
                    throw new IllegalArgumentException("Native 物理帧身份无效");
                }
            }
            frames = List.copyOf(frames);
        }
    }

    /** 原物理帧身份和独立还原结果。 */
    public record Frame(
            /** 原帧序号。 */ Integer index,
            /** 原模块引用。 */ String moduleId,
            /** 原相对 PC。 */ String relativePc,
            /** 采集时函数名称。 */ String functionName,
            /** 采集时函数偏移。 */ String functionOffset,
            /** 后台还原结果。 */ Resolution resolution) {
        /** 已成功帧必须与可靠的原模块和相对 PC 对应。 */
        public Frame {
            if (index == null || index < 0 || resolution == null
                    || relativePc != null && !relativePc.matches("0x(?:0|[1-9a-f][0-9a-f]{0,15})")
                    || functionOffset != null && !functionOffset.matches("0x(?:0|[1-9a-f][0-9a-f]{0,15})")
                    || !"not_applicable".equals(resolution.status()) && (moduleId == null || relativePc == null)) {
                throw new IllegalArgumentException("Native 物理帧身份无效");
            }
        }
    }

    /** 实际使用或尝试的不可变符号文件来源。 */
    public record Source(
            /** NATIVE_SO 或 MAPPING。 */ String type,
            /** 符号表身份。 */ UUID symbolFileId,
            /** 固定修订。 */ Integer revision,
            /** 固定内容摘要。 */ String sha256) {
        /** 不允许缺失来源或使用无效修订与摘要。 */
        public Source {
            if (type == null || !Set.of("NATIVE_SO", "MAPPING").contains(type)
                    || symbolFileId == null || revision == null || revision < 1 || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("符号文件来源无效");
            }
        }
    }

    /** 一项有序内联调用；位置未知时保留空值。 */
    public record Inline(
            /** 真实函数名，可在个别内联项未知。 */ String function,
            /** 无目录的源码文件名。 */ String file,
            /** 正数行号。 */ Integer line,
            /** 正数列号。 */ Integer column) {
        /** 源码路径不得包含编译机目录，位置不能伪造零或负数。 */
        public Inline {
            if (file != null && (file.contains("/") || file.contains("\\"))
                    || line != null && line < 1 || column != null && column < 1) {
                throw new IllegalArgumentException("Native 源码位置无效");
            }
        }
    }

    /** 一帧的当前状态、完整成功结果与实际尝试来源。 */
    public record Resolution(
            /** pending、failed、symbolicated 或 not_applicable。 */ String status,
            /** 安全原因。 */ String reason,
            /** 完整有序内联链。 */ List<Inline> inline,
            /** 首次成功的 SO 来源。 */ Source source,
            /** 最近一次实际尝试的 SO 来源。 */ Source attemptedSource,
            /** 成功或实际尝试时间。 */ Instant processedAt,
            /** 临时故障的重试时间。 */ Instant retryAt) {
        /** 成功必须有真实函数、来源和时间；其他状态不得伪造成功结果。 */
        public Resolution {
            if (status == null || !Set.of("pending", "failed", "symbolicated", "not_applicable").contains(status)) {
                throw new IllegalArgumentException("Native 帧还原状态无效");
            }
            if (source != null && !"NATIVE_SO".equals(source.type())
                    || attemptedSource != null && !"NATIVE_SO".equals(attemptedSource.type())) {
                throw new IllegalArgumentException("Native 帧来源类型无效");
            }
            if ("symbolicated".equals(status)) {
                if (source == null || processedAt == null || inline == null || inline.isEmpty() || inline.size() > 32
                        || inline.stream().anyMatch(java.util.Objects::isNull)
                        || retryAt != null || attemptedSource != null && !attemptedSource.equals(source)
                        || inline.stream().noneMatch(item -> item.function() != null && !item.function().isBlank() && !item.function().equals("??"))) {
                    throw new IllegalArgumentException("Native 成功帧缺少函数或来源");
                }
                inline = List.copyOf(inline);
            } else if (source != null || (inline != null && !inline.isEmpty())) {
                throw new IllegalArgumentException("未成功帧不能携带成功结果");
            } else if (reason == null || reason.isBlank() || "not_applicable".equals(status)
                    && (attemptedSource != null || processedAt != null || retryAt != null)) {
                throw new IllegalArgumentException("未成功帧缺少原因或携带无效尝试");
            }
        }
    }

    /** 关联 Java 栈独立保留采集状态、当前栈和 mapping 来源。 */
    public record JavaStack(
            /** 原采集状态。 */ String status,
            /** 原采集原因。 */ String reason,
            /** 采集是否截断。 */ Boolean truncated,
            /** 原采集时间。 */ Long capturedAt,
            /** 独立还原状态。 */ String symbolicationStatus,
            /** 安全处理原因。 */ String symbolicationReason,
            /** 唯一当前栈。 */ CrashThreadStack.Stack stack,
            /** 成功 mapping 来源。 */ Source source,
            /** 尝试的 mapping 来源。 */ Source attemptedSource,
            /** 成功或尝试时间。 */ Instant processedAt,
            /** 临时故障重试时间。 */ Instant retryAt) {
        /** 验证 Java 栈状态和来源一致性。 */
        public JavaStack {
            if (symbolicationStatus == null || !Set.of("pending", "failed", "symbolicated", "not_applicable").contains(symbolicationStatus)
                    || stack == null) throw new IllegalArgumentException("关联 Java 栈状态无效");
            if (source != null && !"MAPPING".equals(source.type())
                    || attemptedSource != null && !"MAPPING".equals(attemptedSource.type())) {
                throw new IllegalArgumentException("Java 栈来源类型无效");
            }
            if ("symbolicated".equals(symbolicationStatus)) {
                if (!"retrace_text".equals(stack.format()) || source == null || processedAt == null || retryAt != null
                        || attemptedSource != null && !attemptedSource.equals(source)) {
                    throw new IllegalArgumentException("Java 栈成功缺少来源");
                }
            } else if (!"raw_frames".equals(stack.format()) || source != null) {
                throw new IllegalArgumentException("未成功 Java 栈必须保留原始帧");
            } else if ("not_applicable".equals(symbolicationStatus) != ((List<?>) stack.content()).isEmpty()) {
                throw new IllegalArgumentException("Java 空栈状态不一致");
            } else if (symbolicationReason == null || symbolicationReason.isBlank()
                    || "not_applicable".equals(symbolicationStatus)
                    && (attemptedSource != null || processedAt != null || retryAt != null)) {
                throw new IllegalArgumentException("Java 栈缺少原因或携带无效尝试");
            }
        }
    }
}
