#ifndef APM_NATIVE_DIAGNOSTICS_H
#define APM_NATIVE_DIAGNOSTICS_H

#include <stdint.h>
#include <sys/types.h>

/** 读取原进程的有界日志、内存用量和文件描述符，并单独提交结构化记录。 */
int apm_write_native_diagnostics(pid_t pid, uint64_t occurred_at, int output_fd);

/** 可选原生文本首次落盘前使用同一敏感值和转储检测规则。 */
int apm_native_text_is_sensitive(const char *text);

#endif
