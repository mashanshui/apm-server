#define _GNU_SOURCE

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "native_trace_protocol.h"
#include "native_diagnostics.h"
#include "xcd_elf.h"
#include "xcd_frames.h"
#include "xcd_maps.h"
#include "xcd_thread.h"
#include "xcd_util.h"
#include "xcc_util.h"

/** 单事件的模块、线程与帧数量上限。 */
#define APM_MODULE_LIMIT 128u
#define APM_CRASH_FRAME_LIMIT 128u
#define APM_OTHER_FRAME_LIMIT 32u
#define APM_OTHER_THREAD_LIMIT 64u
#define APM_FRAME_LIMIT (APM_CRASH_FRAME_LIMIT + APM_OTHER_THREAD_LIMIT * APM_OTHER_FRAME_LIMIT)
/** 无可靠模块时不能写入未经归属的相对地址。 */
#define APM_NO_MODULE UINT32_MAX
/** 不安全或超长的可选文本直接省略。 */
#define APM_NAME_BYTES 129u
#define APM_FUNCTION_BYTES 97u
/** abort 消息只保留有界、安全的完整文本。 */
#define APM_ABORT_BYTES 256u
/** 回溯分区状态。 */
#define APM_STATUS_OK 0u
#define APM_STATUS_PARTIAL 1u
#define APM_STATUS_UNAVAILABLE 2u

/** 模块路径仅暂存在内存，用于区分不同 ELF；最终只输出安全文件名。 */
typedef struct {
    /** 由 maps 对象持有的内部路径。 */ const char *internal_path;
    /** 区分 APK 内嵌 ELF 的内部偏移。 */ size_t internal_offset;
    /** 已校验的模块文件名。 */ char name[APM_NAME_BYTES];
    /** 从当前 ELF 读取的 Build ID。 */ uint8_t build_id[64];
    /** Build ID 字节数。 */ size_t build_id_size;
} apm_module;

/** 单帧只保存相对地址和可选函数名。 */
typedef struct {
    /** 事件内模块下标。 */ uint32_t module_index;
    /** 模块相对 PC。 */ uint64_t relative_pc;
    /** 函数相对偏移。 */ uint64_t function_offset;
    /** 已校验的函数名。 */ char function[APM_FUNCTION_BYTES];
} apm_frame;

/** 一个线程的 TID 与独立回溯状态。 */
typedef struct {
    /** 原系统线程编号。 */ pid_t tid;
    /** 此线程的采集状态。 */ uint32_t status;
    /** 是否因数量限制截断。 */ uint32_t truncated;
    /** 已校验线程名，可为空。 */ char name[APM_NAME_BYTES];
    /** 全局帧数组起点。 */ size_t first_frame;
    /** 此线程帧数。 */ size_t frame_count;
} apm_thread;

/** 独立 dumper 的有界结果，临时 maps 对象不会被写入。 */
typedef struct {
    /** 仅经管道接收的原进程请求。 */ apm_trace_request request;
    /** 用于回溯的内存中映射。 */ xcd_maps_t *maps;
    /** 有界模块字典。 */ apm_module modules[APM_MODULE_LIMIT];
    /** 模块数量。 */ size_t module_count;
    /** 崩溃线程和其他线程。 */ apm_thread threads[1u + APM_OTHER_THREAD_LIMIT];
    /** 实际记录线程数。 */ size_t thread_count;
    /** 观测到的其他线程数。 */ uint32_t observed_other_threads;
    /** 线程目录采集状态。 */ uint32_t thread_collection_status;
    /** 全部线程的有界帧数组。 */ apm_frame frames[APM_FRAME_LIMIT];
    /** 已使用帧数。 */ size_t frame_count;
    /** 已校验的可选 abort 消息；不安全或不可读取时为空。 */ char abort_message[APM_ABORT_BYTES];
} apm_capture;

/** 帧访问回调的当前结果和线程。 */
typedef struct {
    /** 本次捕获结果。 */ apm_capture *capture;
    /** 正在处理的线程。 */ apm_thread *thread;
} apm_visit_context;

/** 带边界检查的二进制输出缓冲区。 */
typedef struct {
    /** 已分配的字节。 */ uint8_t *bytes;
    /** 当前写入位置。 */ size_t position;
    /** 容量。 */ size_t capacity;
    /** 是否发生越界。 */ int failed;
} apm_writer;

/** 只执行有限读取，避免请求未完整到达就开始采集。 */
static int apm_read_all(int fd, void *target, size_t length) {
    uint8_t *bytes = target;
    size_t offset = 0;
    while (offset < length) {
        ssize_t count = read(fd, bytes + offset, length - offset);
        if (count > 0) offset += (size_t) count;
        else if (count < 0 && errno == EINTR) continue;
        else return -1;
    }
    return 0;
}

/** 只在完整结构化结果形成后提交，半写由尾部校验识别。 */
static int apm_write_all(int fd, const uint8_t *bytes, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t count = write(fd, bytes + offset, length - offset);
        if (count > 0) offset += (size_t) count;
        else if (count < 0 && errno == EINTR) continue;
        else return -1;
    }
    return 0;
}

/** 检查不区分大小写的敏感词，不记录待检查文本。 */
static int apm_contains_word(const char *text, const char *word) {
    size_t word_length = strlen(word);
    for (size_t start = 0; text[start] != '\0'; start++) {
        size_t index = 0;
        while (index < word_length && text[start + index] != '\0' &&
               tolower((unsigned char) text[start + index]) == word[index]) index++;
        if (index == word_length) return 1;
    }
    return 0;
}

/** 可选文本只允许有限 ASCII 标识符；不安全内容直接省略。 */
static int apm_safe_name(const char *source, char *target, size_t capacity) {
    if (source == NULL || capacity < 2) return 0;
    size_t length = strnlen(source, capacity);
    if (length == 0 || length >= capacity) return 0;
    const char *blocked[] = {"token", "password", "secret", "cookie", "authorization", "bearer", "api_key", "apikey",
                             "app-key", "app_key", "appkey", "apm_ak_", "apm_qt_"};
    for (size_t index = 0; index < sizeof(blocked) / sizeof(blocked[0]); index++) {
        if (apm_contains_word(source, blocked[index])) return 0;
    }
    unsigned int digits_in_sequence = 0;
    for (size_t index = 0; index < length; index++) {
        unsigned char value = (unsigned char) source[index];
        if (!(isalnum(value) || value == '_' || value == '.' || value == '-' || value == '$' || value == '+')) return 0;
        if (isdigit(value)) digits_in_sequence++;
        else if (value != '-') digits_in_sequence = 0;
        if (digits_in_sequence >= 8) return 0;
        if (index + 36 <= length && source[index + 8] == '-' && source[index + 13] == '-' &&
            source[index + 18] == '-' && source[index + 23] == '-') {
            int uuid = 1;
            for (size_t offset = 0; offset < 36; offset++) {
                if (offset == 8 || offset == 13 || offset == 18 || offset == 23) continue;
                if (!isxdigit((unsigned char) source[index + offset])) { uuid = 0; break; }
            }
            if (uuid) return 0;
        }
    }
    memcpy(target, source, length);
    target[length] = '\0';
    return 1;
}

/** 读取线程名，并在首次输出前校验。 */
static void apm_read_thread_name(pid_t pid, pid_t tid, char *target, size_t capacity) {
    char path[96];
    char name[APM_NAME_BYTES];
    int count = snprintf(path, sizeof(path), "/proc/%d/task/%d/comm", (int) pid, (int) tid);
    if (count <= 0 || (size_t) count >= sizeof(path)) return;
    FILE *input = fopen(path, "re");
    if (input == NULL) return;
    if (fgets(name, sizeof(name), input) != NULL) {
        name[strcspn(name, "\r\n")] = '\0';
        (void) apm_safe_name(name, target, capacity);
    }
    (void) fclose(input);
}

/** 从实际帧的 ELF 添加模块，不按同名模块猜测 Build ID。 */
static uint32_t apm_module_for(apm_capture *capture, xcd_map_t *map) {
    if (map == NULL || map->name == NULL || map->name[0] != '/') return APM_NO_MODULE;
    xcd_elf_t *elf = xcd_map_get_elf(map, capture->request.pid, capture->maps);
    // 无法打开 ELF 时无法可靠确认加载偏移，不把估算值伪装成模块相对 PC。
    if (elf == NULL) return APM_NO_MODULE;
    for (size_t index = 0; index < capture->module_count; index++) {
        apm_module *module = &capture->modules[index];
        if (module->internal_offset == map->elf_start_offset && strcmp(module->internal_path, map->name) == 0) return (uint32_t) index;
    }
    if (capture->module_count >= APM_MODULE_LIMIT) return APM_NO_MODULE;
    const char *name = strrchr(map->name, '/');
    name = name == NULL ? map->name : name + 1;
    if (map->elf_start_offset != 0) {
        // APK 中的模块只能使用对应 ELF 自身的 SONAME。
        name = xcd_elf_get_so_name(elf);
    }
    apm_module *module = &capture->modules[capture->module_count];
    if (!apm_safe_name(name, module->name, sizeof(module->name))) return APM_NO_MODULE;
    module->internal_path = map->name;
    module->internal_offset = map->elf_start_offset;
    if (elf != NULL) {
        size_t length = 0;
        if (xcd_elf_get_build_id(elf, module->build_id, sizeof(module->build_id), &length) == 0 && length <= sizeof(module->build_id)) {
            module->build_id_size = length;
        }
    }
    return (uint32_t) capture->module_count++;
}

/** 只复制回溯帧和相对 PC，不生成原始栈或完整映射内容。 */
static int apm_visit_frame(const xcd_frame_view_t *view, void *opaque) {
    apm_visit_context *context = opaque;
    apm_capture *capture = context->capture;
    if (capture->frame_count >= APM_FRAME_LIMIT) return -1;
    apm_frame *frame = &capture->frames[capture->frame_count++];
    frame->module_index = apm_module_for(capture, view->map);
    if (frame->module_index != APM_NO_MODULE) frame->relative_pc = (uint64_t) view->relative_pc;
    else context->thread->status = APM_STATUS_PARTIAL;
    if (apm_safe_name(view->function_name, frame->function, sizeof(frame->function))) frame->function_offset = (uint64_t) view->function_offset;
    context->thread->frame_count++;
    return 0;
}

/** 在线程附着期间只读取完整、有界且可安全脱敏的 abort 消息，不输出映射或原始内存。 */
static void apm_capture_abort_message(apm_capture *capture) {
    if (capture->request.signal_number != SIGABRT || capture->maps == NULL) return;
    /** abort 消息的进程内地址。 */
    uintptr_t address = xcd_maps_find_abort_msg(capture->maps);
    /** 记录中的非消息字节及结尾零字节。 */
    size_t overhead = sizeof(size_t) + 1u;
    if (address != 0) {
        // Android 10 及以上的匿名 abort 映射以前导 magic 开始。
        address += 2u * sizeof(uint64_t);
        overhead += 2u * sizeof(uint64_t);
    } else {
        // 旧系统通过 libc 的 abort 消息指针取得同一有界记录。
        /** 旧版 libc 导出的间接指针地址。 */
        uintptr_t symbol = xcd_maps_find_pc(capture->maps, XCC_UTIL_LIBC, XCC_UTIL_LIBC_ABORT_MSG_PTR);
        /** 旧版 abort 消息的一级间接指针。 */
        uintptr_t indirect = 0;
        if (symbol == 0 || xcd_util_ptrace_read_fully(capture->request.pid, symbol, &indirect, sizeof(indirect)) != 0 ||
            indirect == 0 || xcd_util_ptrace_read_fully(capture->request.pid, indirect, &address, sizeof(address)) != 0 ||
            address == 0) return;
    }
    /** 系统记录声明的总字节数。 */
    size_t record_size = 0;
    if (xcd_util_ptrace_read_fully(capture->request.pid, address, &record_size, sizeof(record_size)) != 0 ||
        record_size <= overhead || record_size - overhead >= APM_ABORT_BYTES) return;
    /** 完整消息字节数，超长消息不做不安全截断。 */
    size_t length = record_size - overhead;
    /** 仅在本进程内存中使用的有限临时缓冲。 */
    char message[APM_ABORT_BYTES] = {0};
    if (xcd_util_ptrace_read_fully(capture->request.pid, address + sizeof(size_t), message, length) != 0 ||
        strnlen(message, length) != length) return;
    for (size_t index = 0; index < length; index++) {
        if ((unsigned char) message[index] < 32u || (unsigned char) message[index] > 126u) return;
    }
    if (!apm_native_text_is_sensitive(message)) memcpy(capture->abort_message, message, length + 1u);
}

/** 暂停单线程回溯；崩溃线程使用原信号上下文寄存器。 */
static void apm_capture_thread(apm_capture *capture, pid_t tid, int crashed) {
    if (capture->thread_count >= 1u + APM_OTHER_THREAD_LIMIT) return;
    apm_thread *output = &capture->threads[capture->thread_count++];
    output->tid = tid;
    output->status = APM_STATUS_OK;
    output->first_frame = capture->frame_count;
    apm_read_thread_name(capture->request.pid, tid, output->name, sizeof(output->name));
    xcd_thread_t thread;
    xcd_thread_init(&thread, capture->request.pid, tid);
    xcd_thread_suspend(&thread);
    int attached = thread.status == XCD_THREAD_STATUS_OK;
    // ptrace 读取以进程主线程为目标；后台 Native 线程崩溃时在主线程附着阶段采集。
    if (attached && tid == capture->request.pid) apm_capture_abort_message(capture);
    if (attached && crashed) thread.regs = capture->request.registers;
    else if (attached) xcd_thread_load_regs(&thread);
    if (!attached || thread.status != XCD_THREAD_STATUS_OK || capture->maps == NULL ||
        xcd_thread_load_frames(&thread, capture->maps) != 0) {
        output->status = APM_STATUS_UNAVAILABLE;
    } else {
        size_t maximum = crashed ? APM_CRASH_FRAME_LIMIT : APM_OTHER_FRAME_LIMIT;
        apm_visit_context context = {capture, output};
        size_t observed = xcd_frames_visit(thread.frames, maximum, apm_visit_frame, &context);
        if (observed > output->frame_count) { output->status = APM_STATUS_PARTIAL; output->truncated = 1; }
        if (output->frame_count == 0) output->status = APM_STATUS_UNAVAILABLE;
        xcd_frames_destroy(&thread.frames);
    }
    // 被中止时内核也会解除 dumper 自己的 ptrace 关系。
    if (attached) xcd_thread_resume(&thread);
}

/** 枚举其他线程，主线程优先；不暂停负责超时回收的工作者。 */
static void apm_capture_threads(apm_capture *capture) {
    char path[64];
    pid_t candidates[APM_OTHER_THREAD_LIMIT];
    size_t count = 0;
    int has_main = 0;
    int length = snprintf(path, sizeof(path), "/proc/%d/task", (int) capture->request.pid);
    if (length <= 0 || (size_t) length >= sizeof(path)) { capture->thread_collection_status = APM_STATUS_UNAVAILABLE; return; }
    DIR *directory = opendir(path);
    if (directory == NULL) { capture->thread_collection_status = APM_STATUS_UNAVAILABLE; return; }
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        char *end = NULL;
        errno = 0;
        long value = strtol(entry->d_name, &end, 10);
        if (errno != 0 || end == entry->d_name || *end != '\0' || value <= 0 || value > INT_MAX) continue;
        pid_t tid = (pid_t) value;
        if (tid == capture->request.tid || tid == capture->request.watchdog_tid) continue;
        capture->observed_other_threads++;
        if (tid == capture->request.pid) has_main = 1;
        else if (count < APM_OTHER_THREAD_LIMIT) candidates[count++] = tid;
    }
    (void) closedir(directory);
    if (has_main) apm_capture_thread(capture, capture->request.pid, 0);
    for (size_t index = 0; index < count && capture->thread_count < 1u + APM_OTHER_THREAD_LIMIT; index++) {
        apm_capture_thread(capture, candidates[index], 0);
    }
    if (capture->observed_other_threads > capture->thread_count - 1) capture->thread_collection_status = APM_STATUS_PARTIAL;
}

/** 将 32 位整数以小端写入有界缓冲区。 */
static void apm_put_u32(apm_writer *writer, uint32_t value) {
    if (writer->position > writer->capacity || writer->capacity - writer->position < 4) { writer->failed = 1; return; }
    for (size_t index = 0; index < 4; index++) writer->bytes[writer->position++] = (uint8_t) (value >> (index * 8));
}

/** 将 64 位地址按原位模式写入。 */
static void apm_put_u64(apm_writer *writer, uint64_t value) {
    if (writer->position > writer->capacity || writer->capacity - writer->position < 8) { writer->failed = 1; return; }
    for (size_t index = 0; index < 8; index++) writer->bytes[writer->position++] = (uint8_t) (value >> (index * 8));
}

/** 将已校验文本写成长度前缀与字节。 */
static void apm_put_string(apm_writer *writer, const char *text) {
    size_t length = strlen(text);
    apm_put_u32(writer, (uint32_t) length);
    if (writer->position > writer->capacity || length > writer->capacity - writer->position) { writer->failed = 1; return; }
    memcpy(writer->bytes + writer->position, text, length);
    writer->position += length;
}

/** 校验有限记录，恢复时据此拒绝尾部半写。 */
static uint32_t apm_checksum(const uint8_t *bytes, size_t length) {
    uint32_t value = 2166136261u;
    for (size_t index = 0; index < length; index++) value = (value ^ bytes[index]) * 16777619u;
    return value;
}

/** 编码模块和线程回溯，不输出用于计算的临时映射。 */
static int apm_commit_capture(apm_capture *capture) {
    apm_writer writer = {calloc(1, APM_TRACE_MAX_BYTES), 0, APM_TRACE_MAX_BYTES, 0};
    if (writer.bytes == NULL) return -1;
    memcpy(writer.bytes, "ANTR", 4);
    writer.position = 4;
    apm_put_u32(&writer, APM_TRACE_RECORD_VERSION);
    apm_put_u32(&writer, 0);
    apm_put_u64(&writer, capture->request.occurred_at);
    apm_put_u32(&writer, (uint32_t) capture->request.pid);
    apm_put_u32(&writer, (uint32_t) capture->request.tid);
    apm_put_u32(&writer, capture->observed_other_threads);
    apm_put_u32(&writer, capture->thread_collection_status);
    apm_put_u32(&writer, (uint32_t) capture->thread_count);
    apm_put_u32(&writer, (uint32_t) capture->module_count);
    const char hex[] = "0123456789abcdef";
    for (size_t index = 0; index < capture->module_count; index++) {
        apm_module *module = &capture->modules[index];
        char build_id[129] = {0};
        for (size_t digit = 0; digit < module->build_id_size; digit++) {
            build_id[digit * 2] = hex[module->build_id[digit] >> 4];
            build_id[digit * 2 + 1] = hex[module->build_id[digit] & 15];
        }
        apm_put_string(&writer, module->name);
        apm_put_string(&writer, build_id);
    }
    for (size_t index = 0; index < capture->thread_count; index++) {
        apm_thread *thread = &capture->threads[index];
        apm_put_u32(&writer, (uint32_t) thread->tid);
        apm_put_u32(&writer, thread->tid == capture->request.pid ? 1u : 0u);
        apm_put_u32(&writer, thread->status);
        apm_put_u32(&writer, thread->truncated);
        apm_put_string(&writer, thread->name);
        apm_put_u32(&writer, (uint32_t) thread->frame_count);
        for (size_t frame_index = 0; frame_index < thread->frame_count; frame_index++) {
            apm_frame *frame = &capture->frames[thread->first_frame + frame_index];
            apm_put_u32(&writer, frame->module_index);
            apm_put_u64(&writer, frame->relative_pc);
            apm_put_u64(&writer, frame->function_offset);
            apm_put_string(&writer, frame->function);
        }
    }
    apm_put_string(&writer, capture->abort_message);
    if (writer.failed || writer.position > writer.capacity - 4) { free(writer.bytes); return -1; }
    uint32_t total = (uint32_t) (writer.position + 4);
    for (size_t index = 0; index < 4; index++) writer.bytes[8 + index] = (uint8_t) (total >> (index * 8));
    apm_put_u32(&writer, apm_checksum(writer.bytes, writer.position));
    int result = writer.failed ? -1 : apm_write_all(STDOUT_FILENO, writer.bytes, writer.position);
    if (result == 0) result = fsync(STDOUT_FILENO);
    free(writer.bytes);
    return result;
}

/** 安装目录内的独立程序只接受预建管道输入。 */
int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) { puts("apm-crash-dumper/1"); return 0; }
    if (argc != 2 || strcmp(argv[1], "--capture") != 0) return 2;
    apm_capture *capture = calloc(1, sizeof(*capture));
    if (capture == NULL) return 3;
    if (apm_read_all(STDIN_FILENO, &capture->request, sizeof(capture->request)) != 0 ||
        capture->request.magic != APM_TRACE_REQUEST_MAGIC || capture->request.version != APM_TRACE_VERSION ||
        capture->request.pid <= 0 || capture->request.tid <= 0 || capture->request.watchdog_tid <= 0 ||
        capture->request.pid != getppid()) { free(capture); return 4; }
    if (xcd_maps_create(&capture->maps, capture->request.pid) != 0 && capture->maps != NULL) xcd_maps_destroy(&capture->maps);
    apm_capture_thread(capture, capture->request.tid, 1);
    apm_capture_threads(capture);
    int result = apm_commit_capture(capture);
    // 回溯先独立提交；诊断超时或失败不能破坏已提交的栈。
    if (result == 0) (void) apm_write_native_diagnostics(capture->request.pid, capture->request.occurred_at, 3);
    if (capture->maps != NULL) xcd_maps_destroy(&capture->maps);
    free(capture);
    return result == 0 ? 0 : 5;
}
