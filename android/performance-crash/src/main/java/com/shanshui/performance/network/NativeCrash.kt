package com.shanshui.performance.network

/** Native 致命信号及与符号表还原无关的原始证据。 */
data class NativeCrash(
    /** 进程实际运行的 Android ABI。 */ val abi: String,
    /** 崩溃线程的系统 TID。 */ val systemTid: Int,
    /** 可取得且已脱敏的线程名称。 */ val threadName: String? = null,
    /** 可可靠判断时的主线程标记。 */ val mainThread: Boolean? = null,
    /** 信号与故障信息。 */ val signal: NativeSignal,
    /** 寄存器及其采集状态。 */ val registers: NativeRegisters,
    /** 本事件主辅帧引用的模块。 */ val modules: List<NativeModule>,
    /** 崩溃线程原始回溯。 */ val backtrace: NativeBacktrace,
    /** 能可靠关联时的 Java 崩溃线程栈。 */ val javaStack: NativeJavaStack? = null,
)

/** Native 信号与可选故障地址。 */
data class NativeSignal(
    /** 信号编号。 */ val number: Int,
    /** 信号名称。 */ val name: String,
    /** 平台信号码。 */ val code: Int,
    /** 可取得的信号码名称。 */ val codeName: String? = null,
    /** 架构位宽内的十六进制地址。 */ val faultAddress: String? = null,
    /** 已脱敏的 abort 消息。 */ val abortMessage: String? = null,
)

/** 可取得的寄存器值，以字符串避免 64 位地址精度丢失。 */
data class NativeRegisters(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否截断。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 寄存器名称和十六进制值。 */ val values: Map<String, String> = emptyMap(),
)

/** 原始回溯中引用的 ELF 模块。 */
data class NativeModule(
    /** 事件内唯一标识。 */ val id: String,
    /** 模块文件名。 */ val name: String,
    /** 可取得且已脱敏的路径。 */ val path: String? = null,
    /** 模块 ABI。 */ val abi: String,
    /** 可取得的 ELF Build ID，与应用 buildId 分开。 */ val elfBuildId: String? = null,
)

/** 单个原始 Native 帧。 */
data class NativeFrame(
    /** 从零开始的帧序号。 */ val index: Int,
    /** 可取得时引用事件模块字典。 */ val moduleId: String? = null,
    /** 可取得的模块相对 PC。 */ val relativePc: String? = null,
    /** 可取得且已脱敏的现场函数名称。 */ val functionName: String? = null,
    /** 可取得的函数偏移。 */ val functionOffset: String? = null,
)

/** 原始 Native 回溯及独立采集状态。 */
data class NativeBacktrace(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否截断。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 有序帧；失败时为空而不合成帧。 */ val frames: List<NativeFrame> = emptyList(),
)

/** 可可靠关联的崩溃线程 Java 栈。 */
data class NativeJavaStack(
    /** 采集状态。 */ val status: String,
    /** 安全原因类别。 */ val reason: String? = null,
    /** 是否截断。 */ val truncated: Boolean = false,
    /** 原采集时间，Unix 毫秒。 */ val capturedAt: Long? = null,
    /** 原始 Java 帧。 */ val frames: List<StackFrame> = emptyList(),
)
