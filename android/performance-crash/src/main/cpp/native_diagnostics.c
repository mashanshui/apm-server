#define _GNU_SOURCE

#include <ctype.h>
#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "native_diagnostics.h"
#include "native_trace_protocol.h"

/** 原生诊断使用独立的、有界的二进制分区。 */
#define APM_LOG_BYTES (48u * 1024u)
#define APM_LOG_LINE_BYTES 2000u
#define APM_LOG_LINES 200u
#define APM_FD_LIMIT 128u
#define APM_SAFE_INTEGER 9007199254740991ull
#define APM_UNKNOWN UINT64_MAX
/** 状态与安全原因的本地协议编号。 */
#define APM_OK 0u
#define APM_PARTIAL 1u
#define APM_UNAVAILABLE 2u
#define APM_REASON_NONE 0u
#define APM_REASON_IO 1u
#define APM_REASON_PERMISSION 2u
#define APM_REASON_TIMEOUT 3u
#define APM_REASON_UNSUPPORTED 4u
#define APM_REASON_SIZE 5u
#define APM_REASON_COUNT 6u

/** 每个日志缓冲区保留独立状态和有限行。 */
typedef struct {
    /** 采集状态。 */ uint32_t status;
    /** 安全原因。 */ uint32_t reason;
    /** 是否裁剪。 */ uint32_t truncated;
    /** 原采集时间。 */ uint64_t captured_at;
    /** 当前行数。 */ size_t count;
    /** 仅保存首次落盘前已处理的日志。 */ char *lines[APM_LOG_LINES];
} apm_log_buffer;

/** 只输出进程内存数值和受限数值摘要。 */
typedef struct {
    /** 采集状态。 */ uint32_t status;
    /** 安全原因。 */ uint32_t reason;
    /** 是否截断。 */ uint32_t truncated;
    /** 实际采集时间。 */ uint64_t captured_at;
    /** RSS、VSS、Swap 与总 PSS，未知为 APM_UNKNOWN。 */ uint64_t rss, vss, swap, pss;
    /** 不含路径或映射内容的受限系统摘要。 */ char meminfo[512];
    /** 不含线程名或映射内容的进程数值摘要。 */ char status_text[512];
    /** 仅含文件描述符限制的安全摘要。 */ char limits[256];
} apm_memory;

/** 只保存 FD 编号与目标类别，不保存目标原文。 */
typedef struct {
    /** 采集状态。 */ uint32_t status;
    /** 安全原因。 */ uint32_t reason;
    /** 是否裁剪。 */ uint32_t truncated;
    /** 实际采集时间。 */ uint64_t captured_at;
    /** 实际观察到的总数，未知为 UINT32_MAX。 */ uint32_t observed;
    /** 保存条数。 */ size_t count;
    /** 描述符编号。 */ uint32_t numbers[APM_FD_LIMIT];
    /** 安全目标类别。 */ uint32_t categories[APM_FD_LIMIT];
} apm_files;

/** 独立诊断结果和全局日志字节预算。 */
typedef struct {
    /** 发生崩溃的进程。 */ pid_t pid;
    /** 原核心发生时间。 */ uint64_t occurred_at;
    /** 日志缓冲区。 */ apm_log_buffer logs[3];
    /** 全部日志有效字节。 */ size_t log_bytes;
    /** 进程内存摘要。 */ apm_memory memory;
    /** 文件描述符摘要。 */ apm_files files;
} apm_diagnostics;

/** 有边界检查的二进制写入器。 */
typedef struct {
    /** 输出缓冲区。 */ uint8_t *bytes;
    /** 当前长度。 */ size_t length;
    /** 输出容量。 */ size_t capacity;
    /** 是否发生越界。 */ int failed;
} apm_diag_writer;

/** 返回 Unix 毫秒；失败时保留零而不伪造时间。 */
static uint64_t apm_now_millis(void) {
    struct timespec value = {0};
    if (clock_gettime(CLOCK_REALTIME, &value) != 0 || value.tv_sec < 0) return 0;
    return (uint64_t) value.tv_sec * 1000u + (uint64_t) value.tv_nsec / 1000000u;
}

/** 返回单调毫秒，用于限制日志进程等待。 */
static uint64_t apm_monotonic_millis(void) {
    struct timespec value = {0};
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0 || value.tv_sec < 0) return 0;
    return (uint64_t) value.tv_sec * 1000u + (uint64_t) value.tv_nsec / 1000000u;
}

/** 检测日志中的映射地址范围或连续机器字，避免通过日志携带内存转储。 */
static int apm_looks_like_memory_dump(const char *text) {
    size_t length = strlen(text);
    for (size_t index = 0; index < length; index++) {
        if (!isxdigit((unsigned char) text[index]) || (index > 0 && isxdigit((unsigned char) text[index - 1]))) continue;
        size_t end = index;
        while (end < length && isxdigit((unsigned char) text[end])) end++;
        if (end - index < 8) { index = end; continue; }
        if (end < length && text[end] == '-') {
            size_t next = end + 1;
            while (next < length && isxdigit((unsigned char) text[next])) next++;
            if (next - end - 1 >= 8 && next < length && text[next] == ' ') return 1;
        }
        unsigned int words = 1;
        size_t cursor = end;
        while (cursor < length && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++;
        while (cursor < length && words < 2) {
            size_t next = cursor;
            while (next < length && isxdigit((unsigned char) text[next])) next++;
            if (next - cursor < 8) break;
            words++;
            cursor = next;
            while (cursor < length && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++;
        }
        // xCrash 的 stack 行可能只有地址和一个机器字，日志标签不能让它绕过过滤。
        if (words >= 2) return 1;
        index = end;
    }
    return 0;
}

/** 忽略大小写查找敏感词，原文只保留在当前进程内存中。 */
int apm_native_text_is_sensitive(const char *text) {
    const char *blocked[] = {"authorization", "cookie", "token", "password", "secret", "api-key", "api_key", "apikey",
                             "app-key", "app_key", "appkey", "apm_ak_", "apm_qt_",
                             "bearer", "http://", "https://", "/data/", "/storage/", "/sdcard/", "/mnt/user/", "/users/", "/home/",
                             "memory near", "memory map", "stack:"};
    for (size_t index = 0; index < sizeof(blocked) / sizeof(blocked[0]); index++) {
        if (strcasestr(text, blocked[index]) != NULL) return 1;
    }
    if (strchr(text, '@') != NULL) return 1;
    if (apm_looks_like_memory_dump(text)) return 1;
    size_t length = strlen(text);
    for (size_t index = 0; index < length; index++) {
        if (index + 36 <= length && text[index + 8] == '-' && text[index + 13] == '-' &&
            text[index + 18] == '-' && text[index + 23] == '-') {
            int uuid = 1;
            for (size_t part = 0; part < 36; part++) {
                if (part == 8 || part == 13 || part == 18 || part == 23) continue;
                if (!isxdigit((unsigned char) text[index + part])) { uuid = 0; break; }
            }
            if (uuid) return 1;
        }
        if (isdigit((unsigned char) text[index])) {
            unsigned int digits = 0;
            size_t end = index;
            while (end < length && (isdigit((unsigned char) text[end]) || text[end] == ' ' || text[end] == '-' ||
                                    text[end] == '(' || text[end] == ')')) {
                if (isdigit((unsigned char) text[end])) digits++;
                end++;
            }
            if (digits >= 8) return 1;
            if (end > index) index = end - 1;
        }
    }
    // 地址范围和连续机器字可能是原始映射或内存转储，整行替换。
    unsigned long long begin = 0, end = 0;
    char permissions[5] = {0};
    if (sscanf(text, "%llx-%llx %4s", &begin, &end, permissions) == 3 && strchr(permissions, 'r') != NULL) return 1;
    return 0;
}

/** 按 threadtime 格式过滤原进程，并去掉易与手机号混淆的时间和 PID 前缀。 */
static const char *apm_log_payload(const char *line, pid_t pid) {
    int owner = 0, tid = 0, offset = 0;
    char priority = 0;
    if (sscanf(line, "%*d-%*d %*d:%*d:%*d.%*d %d %d %c %n", &owner, &tid, &priority, &offset) != 3 ||
        owner != pid || tid <= 0 || offset <= 0) return NULL;
    return line + offset;
}

/** 将单行脱敏后放入有限缓冲；满额时保留较新的日志。 */
static void apm_add_log(apm_diagnostics *result, apm_log_buffer *buffer, const char *line, size_t maximum) {
    const char *payload = apm_log_payload(line, result->pid);
    if (payload == NULL) return;
    const char *safe = apm_native_text_is_sensitive(payload) ? "[redacted]" : payload;
    size_t length = strlen(safe);
    if (length > APM_LOG_LINE_BYTES) { length = APM_LOG_LINE_BYTES; buffer->truncated = 1; buffer->reason = APM_REASON_SIZE; }
    if (buffer->count >= maximum) {
        result->log_bytes -= strlen(buffer->lines[0]);
        free(buffer->lines[0]);
        memmove(buffer->lines, buffer->lines + 1, (buffer->count - 1) * sizeof(buffer->lines[0]));
        buffer->count--;
        buffer->truncated = 1;
        if (buffer->reason == APM_REASON_NONE) buffer->reason = APM_REASON_COUNT;
    }
    if (result->log_bytes + length > APM_LOG_BYTES) {
        buffer->truncated = 1;
        buffer->reason = APM_REASON_SIZE;
        return;
    }
    char *copy = malloc(length + 1);
    if (copy == NULL) { buffer->truncated = 1; buffer->reason = APM_REASON_SIZE; return; }
    memcpy(copy, safe, length);
    copy[length] = '\0';
    buffer->lines[buffer->count++] = copy;
    result->log_bytes += length;
}

/** 从有限管道数据切分日志行，不把半行或未经处理文本写盘。 */
static void apm_consume_log(apm_diagnostics *result, apm_log_buffer *buffer, size_t maximum,
                            char *line, size_t *line_size, int *overflow, const char *bytes, size_t length) {
    for (size_t index = 0; index < length; index++) {
        if (bytes[index] == '\n') {
            line[*line_size] = '\0';
            if (!*overflow) apm_add_log(result, buffer, line, maximum);
            *line_size = 0;
            *overflow = 0;
        } else if (*line_size < APM_LOG_LINE_BYTES) {
            line[(*line_size)++] = bytes[index];
        } else {
            buffer->truncated = 1;
            buffer->reason = APM_REASON_SIZE;
            *overflow = 1;
        }
    }
}

/** 使用固定 logcat 参数和超时读取单个缓冲区。 */
static void apm_collect_log(apm_diagnostics *result, apm_log_buffer *buffer, const char *name, size_t maximum) {
    buffer->captured_at = apm_now_millis();
    int descriptors[2];
    if (pipe2(descriptors, O_CLOEXEC) != 0) { buffer->status = APM_UNAVAILABLE; buffer->reason = APM_REASON_IO; return; }
    pid_t parent = getpid();
    pid_t child = fork();
    if (child == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(125);
        (void) close(descriptors[0]);
        if (dup2(descriptors[1], STDOUT_FILENO) < 0) _exit(124);
        (void) close(descriptors[1]);
        (void) close(3);
        execl("/system/bin/logcat", "logcat", "-b", name, "-d", "-v", "threadtime", "-t", "1000", (char *) NULL);
        // 权限策略拒绝执行时与普通 IO 失败分开，保持调用方的缺失语义。
        _exit(errno == EACCES || errno == EPERM ? 126 : 127);
    }
    (void) close(descriptors[1]);
    if (child < 0) { (void) close(descriptors[0]); buffer->status = APM_UNAVAILABLE; buffer->reason = APM_REASON_IO; return; }
    (void) fcntl(descriptors[0], F_SETFL, O_NONBLOCK);
    uint64_t start = apm_monotonic_millis();
    char chunk[1024];
    char line[APM_LOG_LINE_BYTES + 1];
    size_t line_size = 0;
    int overflow = 0;
    int eof = 0;
    for (unsigned int attempt = 0; attempt < 75 && !eof; attempt++) {
        struct pollfd descriptor = {.fd = descriptors[0], .events = POLLIN | POLLHUP};
        int ready = poll(&descriptor, 1, 10);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) break;
        if (ready > 0) {
            ssize_t count;
            while ((count = read(descriptors[0], chunk, sizeof(chunk))) > 0) {
                apm_consume_log(result, buffer, maximum, line, &line_size, &overflow, chunk, (size_t) count);
            }
            if (count == 0) eof = 1;
        }
        uint64_t now = apm_monotonic_millis();
        if (start != 0 && now >= start && now - start >= 750u) break;
    }
    (void) close(descriptors[0]);
    int status = 0;
    pid_t waited = 0;
    // EOF 后 logcat 可能尚未完成退出；短暂等待回收，避免把正常退出误判成超时。
    for (unsigned int attempt = 0; attempt < 10 && waited == 0; attempt++) {
        waited = waitpid(child, &status, WNOHANG);
        if (waited == 0 && eof) (void) poll(NULL, 0, 5);
        else if (!eof) break;
    }
    if (waited == 0) {
        (void) kill(child, SIGKILL);
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) { }
        buffer->reason = APM_REASON_TIMEOUT;
        buffer->truncated = 1;
    } else if (waited < 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        buffer->reason = waited > 0 && WIFEXITED(status) && WEXITSTATUS(status) == 126 ? APM_REASON_PERMISSION : APM_REASON_IO;
        // 仅记录固定缓冲区名称及退出类别，不输出 logcat 内容或私有路径。
        (void) __android_log_print(ANDROID_LOG_WARN, "ApmNativeCrash", "logcat unavailable: buffer=%s exit=%d signal=%d",
                                   name, waited > 0 && WIFEXITED(status) ? WEXITSTATUS(status) : -1,
                                   waited > 0 && WIFSIGNALED(status) ? WTERMSIG(status) : 0);
    }
    if (buffer->reason == APM_REASON_NONE) buffer->status = APM_OK;
    else buffer->status = buffer->count == 0 ? APM_UNAVAILABLE : APM_PARTIAL;
}

/** 将 KiB 安全换算为 JavaScript 可精确表达的字节。 */
static uint64_t apm_kib_to_bytes(unsigned long long value) {
    return value <= APM_SAFE_INTEGER / 1024u ? (uint64_t) value * 1024u : APM_UNKNOWN;
}

/** 将已知数字字段追加到固定摘要，不复制 proc 的其他原始文本。 */
static void apm_append_metric(char *target, size_t capacity, const char *name, unsigned long long value) {
    size_t used = strlen(target);
    if (used >= capacity) return;
    (void) snprintf(target + used, capacity - used, "%s: %llu kB\n", name, value);
}

/** 只读取内存数值字段，不保存 proc 中的进程名称或虚拟映射。 */
static void apm_collect_memory(apm_diagnostics *result) {
    apm_memory *memory = &result->memory;
    memory->captured_at = apm_now_millis();
    memory->rss = memory->vss = memory->swap = memory->pss = APM_UNKNOWN;
    char path[80];
    char line[512];
    (void) snprintf(path, sizeof(path), "/proc/%d/status", (int) result->pid);
    FILE *input = fopen(path, "re");
    if (input != NULL) {
        while (fgets(line, sizeof(line), input) != NULL) {
            char key[32]; unsigned long long value; char unit[8];
            if (sscanf(line, "%31[^:]: %llu %7s", key, &value, unit) != 3 || strcmp(unit, "kB") != 0) continue;
            uint64_t bytes = apm_kib_to_bytes(value);
            if (strcmp(key, "VmRSS") == 0) memory->rss = bytes;
            else if (strcmp(key, "VmSize") == 0) memory->vss = bytes;
            else if (strcmp(key, "VmSwap") == 0) memory->swap = bytes;
            else continue;
            if (bytes != APM_UNKNOWN) apm_append_metric(memory->status_text, sizeof(memory->status_text), key, value);
        }
        (void) fclose(input);
    }
    input = fopen("/proc/meminfo", "re");
    if (input != NULL) {
        while (fgets(line, sizeof(line), input) != NULL) {
            char key[32]; unsigned long long value; char unit[8];
            if (sscanf(line, "%31[^:]: %llu %7s", key, &value, unit) != 3 || strcmp(unit, "kB") != 0) continue;
            if ((strcmp(key, "MemTotal") == 0 || strcmp(key, "MemFree") == 0 || strcmp(key, "MemAvailable") == 0) &&
                apm_kib_to_bytes(value) != APM_UNKNOWN) apm_append_metric(memory->meminfo, sizeof(memory->meminfo), key, value);
        }
        (void) fclose(input);
    }
    (void) snprintf(path, sizeof(path), "/proc/%d/smaps_rollup", (int) result->pid);
    input = fopen(path, "re");
    if (input != NULL) {
        while (fgets(line, sizeof(line), input) != NULL) {
            unsigned long long value;
            if (sscanf(line, "Pss: %llu kB", &value) == 1) { memory->pss = apm_kib_to_bytes(value); break; }
        }
        (void) fclose(input);
    }
    (void) snprintf(path, sizeof(path), "/proc/%d/limits", (int) result->pid);
    input = fopen(path, "re");
    if (input != NULL) {
        while (fgets(line, sizeof(line), input) != NULL) {
            char soft[32], hard[32];
            if (sscanf(line, "Max open files %31s %31s", soft, hard) == 2 &&
                strspn(soft, "0123456789") == strlen(soft) && strspn(hard, "0123456789") == strlen(hard)) {
                (void) snprintf(memory->limits, sizeof(memory->limits), "Max open files %s %s\n", soft, hard);
                break;
            }
        }
        (void) fclose(input);
    }
    int available = memory->rss != APM_UNKNOWN || memory->vss != APM_UNKNOWN || memory->swap != APM_UNKNOWN ||
        memory->pss != APM_UNKNOWN || memory->meminfo[0] != '\0' || memory->limits[0] != '\0';
    memory->status = available ? APM_PARTIAL : APM_UNAVAILABLE;
    memory->reason = available ? APM_REASON_UNSUPPORTED : APM_REASON_IO;
}

/** 将原 FD 目标归并为安全类别，绝不输出路径或套接字编号。 */
static uint32_t apm_fd_category(const char *target) {
    if (strncmp(target, "socket:", 7) == 0) return 1;
    if (strncmp(target, "pipe:", 5) == 0) return 2;
    if (strncmp(target, "anon_inode:", 11) == 0) return 3;
    if (strncmp(target, "/dev/", 5) == 0) return 4;
    if (target[0] == '/') return 5;
    return 6;
}

/** 读取原进程 FD 数量与最多 128 条类别。 */
static void apm_collect_files(apm_diagnostics *result) {
    apm_files *files = &result->files;
    files->captured_at = apm_now_millis();
    char path[96];
    (void) snprintf(path, sizeof(path), "/proc/%d/fd", (int) result->pid);
    DIR *directory = opendir(path);
    if (directory == NULL) {
        files->status = APM_UNAVAILABLE;
        files->reason = errno == EACCES || errno == EPERM ? APM_REASON_PERMISSION : APM_REASON_IO;
        files->observed = UINT32_MAX;
        return;
    }
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        char *end = NULL;
        errno = 0;
        long number = strtol(entry->d_name, &end, 10);
        if (errno != 0 || end == entry->d_name || *end != '\0' || number < 0 || number > INT_MAX) continue;
        files->observed++;
        if (files->count >= APM_FD_LIMIT) { files->truncated = 1; files->reason = APM_REASON_COUNT; continue; }
        (void) snprintf(path, sizeof(path), "/proc/%d/fd/%ld", (int) result->pid, number);
        char target[512];
        ssize_t length = readlink(path, target, sizeof(target) - 1);
        uint32_t category = 0;
        if (length >= 0) { target[length] = '\0'; category = apm_fd_category(target); }
        else if (files->reason == APM_REASON_NONE) files->reason = APM_REASON_IO;
        files->numbers[files->count] = (uint32_t) number;
        files->categories[files->count++] = category;
    }
    (void) closedir(directory);
    files->status = files->reason == APM_REASON_NONE ? APM_OK : APM_PARTIAL;
}

/** 在固定缓冲区中写入小端整数。 */
static void apm_diag_u32(apm_diag_writer *writer, uint32_t value) {
    if (writer->length > writer->capacity || writer->capacity - writer->length < 4) { writer->failed = 1; return; }
    for (size_t index = 0; index < 4; index++) writer->bytes[writer->length++] = (uint8_t) (value >> (index * 8));
}

/** 在固定缓冲区中写入无损 64 位值。 */
static void apm_diag_u64(apm_diag_writer *writer, uint64_t value) {
    if (writer->length > writer->capacity || writer->capacity - writer->length < 8) { writer->failed = 1; return; }
    for (size_t index = 0; index < 8; index++) writer->bytes[writer->length++] = (uint8_t) (value >> (index * 8));
}

/** 写入已脱敏文本及其字节长度。 */
static void apm_diag_text(apm_diag_writer *writer, const char *text) {
    size_t length = strlen(text);
    apm_diag_u32(writer, (uint32_t) length);
    if (writer->length > writer->capacity || length > writer->capacity - writer->length) { writer->failed = 1; return; }
    memcpy(writer->bytes + writer->length, text, length);
    writer->length += length;
}

/** 计算诊断分区的 FNV-1a 完整性校验。 */
static uint32_t apm_diag_checksum(const uint8_t *bytes, size_t length) {
    uint32_t value = 2166136261u;
    for (size_t index = 0; index < length; index++) value = (value ^ bytes[index]) * 16777619u;
    return value;
}

/** 将已处理的日志、内存和 FD 一次性写入独立分区。 */
static int apm_commit_diagnostics(apm_diagnostics *result, int fd) {
    apm_diag_writer writer = {calloc(1, APM_DIAGNOSTICS_MAX_BYTES), 0, APM_DIAGNOSTICS_MAX_BYTES, 0};
    if (writer.bytes == NULL) return -1;
    memcpy(writer.bytes, "ANDG", 4); writer.length = 4;
    apm_diag_u32(&writer, 1); apm_diag_u32(&writer, 0); apm_diag_u64(&writer, result->occurred_at);
    apm_diag_u32(&writer, (uint32_t) result->pid);
    for (size_t index = 0; index < 3; index++) {
        apm_log_buffer *buffer = &result->logs[index];
        apm_diag_u32(&writer, buffer->status); apm_diag_u32(&writer, buffer->reason); apm_diag_u32(&writer, buffer->truncated);
        apm_diag_u64(&writer, buffer->captured_at); apm_diag_u32(&writer, (uint32_t) buffer->count);
        for (size_t line = 0; line < buffer->count; line++) apm_diag_text(&writer, buffer->lines[line]);
    }
    apm_memory *memory = &result->memory;
    apm_diag_u32(&writer, memory->status); apm_diag_u32(&writer, memory->reason); apm_diag_u32(&writer, memory->truncated);
    apm_diag_u64(&writer, memory->captured_at);
    apm_diag_u64(&writer, memory->rss); apm_diag_u64(&writer, memory->vss);
    apm_diag_u64(&writer, memory->swap); apm_diag_u64(&writer, memory->pss);
    apm_diag_text(&writer, memory->meminfo); apm_diag_text(&writer, memory->status_text); apm_diag_text(&writer, memory->limits);
    apm_files *files = &result->files;
    apm_diag_u32(&writer, files->status); apm_diag_u32(&writer, files->reason); apm_diag_u32(&writer, files->truncated);
    apm_diag_u64(&writer, files->captured_at); apm_diag_u32(&writer, files->observed); apm_diag_u32(&writer, (uint32_t) files->count);
    for (size_t index = 0; index < files->count; index++) { apm_diag_u32(&writer, files->numbers[index]); apm_diag_u32(&writer, files->categories[index]); }
    if (writer.failed || writer.length > writer.capacity - 4) { free(writer.bytes); return -1; }
    uint32_t total = (uint32_t) (writer.length + 4);
    for (size_t index = 0; index < 4; index++) writer.bytes[8 + index] = (uint8_t) (total >> (index * 8));
    apm_diag_u32(&writer, apm_diag_checksum(writer.bytes, writer.length));
    size_t written = 0;
    while (written < writer.length) {
        ssize_t count = write(fd, writer.bytes + written, writer.length - written);
        if (count > 0) written += (size_t) count;
        else if (count < 0 && errno == EINTR) continue;
        else break;
    }
    int success = written == writer.length && fsync(fd) == 0;
    free(writer.bytes);
    return success ? 0 : -1;
}

/** 读取崩溃原进程并提交独立诊断；失败不会回滚已保存的核心和回溯。 */
int apm_write_native_diagnostics(pid_t pid, uint64_t occurred_at, int output_fd) {
    apm_diagnostics *result = calloc(1, sizeof(*result));
    if (result == NULL) return -1;
    result->pid = pid; result->occurred_at = occurred_at;
    apm_collect_memory(result);
    apm_collect_files(result);
    apm_collect_log(result, &result->logs[0], "main", 200);
    apm_collect_log(result, &result->logs[1], "system", 50);
    apm_collect_log(result, &result->logs[2], "events", 50);
    int written = apm_commit_diagnostics(result, output_fd);
    for (size_t buffer = 0; buffer < 3; buffer++) {
        for (size_t line = 0; line < result->logs[buffer].count; line++) free(result->logs[buffer].lines[line]);
    }
    free(result);
    return written;
}
