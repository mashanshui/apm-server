package com.shanshui.apmserver.crash.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shanshui.apmserver.telemetry.api.StackFrame;

import java.util.List;
import java.util.Map;

/** Native 致命信号的原始材料，不包含内存转储或共享诊断。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NativeCrash(
        /** 当前进程实际运行的 Android ABI。 */ String abi,
        /** 崩溃线程的系统 TID。 */ Integer systemTid,
        /** 可取得且已脱敏的线程名。 */ String threadName,
        /** 可可靠判断时的主线程标记。 */ Boolean mainThread,
        /** 信号及故障原因。 */ Signal signal,
        /** 现场寄存器，地址以十六进制字符串表示。 */ Registers registers,
        /** 主辅堆栈引用的模块字典。 */ List<Module> modules,
        /** 崩溃线程的原始回溯。 */ Backtrace backtrace,
        /** 仅在能够可靠关联时提供的 Java 栈。 */ JavaStack javaStack) {

    /** 信号编号、code 和可取得的故障信息。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Signal(
            /** 信号编号。 */ Integer number,
            /** 信号名称。 */ String name,
            /** 平台提供的信号 code。 */ Integer code,
            /** 可取得的 code 名称。 */ String codeName,
            /** 可取得的故障地址，零地址也是有效值。 */ String faultAddress,
            /** 已脱敏的 abort 消息。 */ String abortMessage) { }

    /** 寄存器及采集状态。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Registers(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否截断。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 寄存器名称到规范十六进制值的映射。 */ Map<String, String> values) { }

    /** 被实际堆栈帧引用的 ELF 模块。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Module(
            /** 事件内唯一模块标识。 */ String id,
            /** SO 文件名。 */ String name,
            /** 可取得且已脱敏的路径。 */ String path,
            /** 模块 ABI。 */ String abi,
            /** 可取得时的 ELF Build ID，与应用 buildId 分开。 */ String elfBuildId) { }

    /** 单个原始 Native 帧。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Frame(
            /** 从零开始的帧序号。 */ Integer index,
            /** 可取得时引用事件模块字典。 */ String moduleId,
            /** 相对于 ELF 模块的程序计数器。 */ String relativePc,
            /** 现场已有且已脱敏的函数名。 */ String functionName,
            /** 可取得的函数偏移。 */ String functionOffset) { }

    /** 原始 Native 回溯及独立的采集状态。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Backtrace(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否截断。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 有序原始帧；失败时可以为空。 */ List<Frame> frames) { }

    /** 已可靠关联的崩溃线程 Java 帧及采集状态。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JavaStack(
            /** 采集状态。 */ String status,
            /** 安全原因类别。 */ String reason,
            /** 是否截断。 */ Boolean truncated,
            /** 原采集时间，Unix 毫秒。 */ Long capturedAt,
            /** 原始 Java 帧。 */ List<StackFrame> frames) { }
}
