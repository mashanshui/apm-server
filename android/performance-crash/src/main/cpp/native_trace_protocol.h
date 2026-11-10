#ifndef APM_NATIVE_TRACE_PROTOCOL_H
#define APM_NATIVE_TRACE_PROTOCOL_H

#include <stdint.h>
#include <sys/types.h>
#include "xcd_regs.h"

/** 父进程和同 ABI dumper 之间仅通过管道传递的请求标记。 */
#define APM_TRACE_REQUEST_MAGIC 0x41504d31u
/** 管道请求和有限回溯记录的协议版本。 */
#define APM_TRACE_VERSION 1u
/** 回溯落盘格式第二版追加有界的可选 abort 消息。 */
#define APM_TRACE_RECORD_VERSION 2u
/** 受限的 Native 辅助记录容量，连同核心和身份不超过 512 KiB。 */
#define APM_TRACE_MAX_BYTES (320u * 1024u)
/** 与身份、核心和回溯分区一起受单份 512 KiB 暂存额度限制。 */
#define APM_DIAGNOSTICS_MAX_BYTES (96u * 1024u)

/** 此结构仅在内存管道中传递，不写入文件。 */
typedef struct {
    /** 管道请求标记。 */ uint32_t magic;
    /** 协议版本。 */ uint32_t version;
    /** 发生崩溃的原进程。 */ pid_t pid;
    /** 发生崩溃的系统线程。 */ pid_t tid;
    /** 为超时回收负责的线程，不允许由 dumper 暂停。 */ pid_t watchdog_tid;
    /** 原始致命信号，仅 SIGABRT 尝试读取有界的 abort 消息。 */ int32_t signal_number;
    /** 崩溃发生时间，Unix 毫秒。 */ uint64_t occurred_at;
    /** 从出错上下文复制的寄存器，不包含原始 ucontext。 */ xcd_regs_t registers;
} apm_trace_request;

#endif
