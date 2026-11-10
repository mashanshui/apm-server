#define _GNU_SOURCE

#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <android/log.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#include "xcd_regs.h"
#include "native_trace_protocol.h"

/** 可恢复核心文件使用的格式版本。 */
#define APM_CORE_VERSION 1u
/** 初始化线程可使用的备用信号栈大小。 */
#define APM_ALT_STACK_BYTES (128u * 1024u)
/** 写入核心时允许的寄存器项数。 */
#define APM_MAX_REGISTERS 64u
/** 核心记录缓冲区容量，足以容纳全部寄存器。 */
#define APM_CORE_BUFFER_BYTES 1200u
/** 独立回溯最多五秒，Java 补充最多两秒，信号入口总等待最多八秒。 */
#define APM_DUMPER_TIMEOUT_MILLIS 5000u
#define APM_CALLBACK_TIMEOUT_NANOS 2000000000LL
#define APM_HANDLER_TIMEOUT_MILLIS 8000u

/** 在崩溃现场记录进程实际 ABI，恢复时不推断为新进程架构。 */
#if defined(__aarch64__)
#define APM_ABI_ID 1u
#elif defined(__arm__)
#define APM_ABI_ID 2u
#elif defined(__i386__)
#define APM_ABI_ID 3u
#elif defined(__x86_64__)
#define APM_ABI_ID 4u
#else
#error Unsupported Android ABI
#endif

/** 一个信号及安装前的处理器。 */
typedef struct {
    /** 信号编号。 */ int number;
    /** 安装前处理器。 */ struct sigaction previous;
    /** 当前安装阶段是否成功。 */ int installed;
} apm_signal_slot;

/** 看护者接收的固定命令，不包含原始内存。 */
typedef struct {
    /** 独立回溯进程。 */ pid_t child;
    /** 原进程。 */ pid_t pid;
    /** 崩溃线程。 */ pid_t tid;
    /** 原发生时间。 */ uint64_t occurred_at;
} apm_worker_request;

/** 需要捕获的致命信号。 */
static apm_signal_slot g_signals[] = {
        {.number = SIGSEGV}, {.number = SIGABRT}, {.number = SIGBUS},
        {.number = SIGILL}, {.number = SIGFPE}
};
/** 常规 JNI 生命周期串行化；信号处理函数不获取此锁。 */
static pthread_mutex_t g_lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;
/** 0 为关闭、1 为监听中、2 为正在保存核心。 */
static volatile sig_atomic_t g_state;
/** 初始化时打开的核心文件，仅信号处理器写入。 */
static int g_core_fd = -1;
/** 初始化线程可选备用栈内存。 */
static void *g_alt_stack;
/** 安装备选栈前的线程状态。 */
static stack_t g_previous_alt_stack;
/** 安装备选栈的系统线程号。 */
static pid_t g_alt_owner;
/** 崩溃前预打开的辅助记录和独立程序路径。 */
static int g_trace_fd = -1;
/** 原生非堆栈诊断使用单独的提交文件。 */
static int g_diagnostics_fd = -1;
/** 信号入口在超时后固定是否接纳 Java 辅助记录。 */
static int g_decision_fd = -1;
static int g_null_fd = -1;
static char g_dumper_path[PATH_MAX];
/** 预创建的请求、工作者命令和完成通知通道。 */
static int g_request_fds[2] = {-1, -1};
static int g_worker_fds[2] = {-1, -1};
static int g_done_fds[2] = {-1, -1};
/** 预启动工作者和其系统 TID。 */
static pthread_t g_worker;
static int g_worker_started;
static volatile sig_atomic_t g_worker_tid;
/** Java 引用仅在普通工作者上下文使用，信号入口不调用 JNI。 */
static JavaVM *g_java_vm;
static jobject g_java_callback;
static jmethodID g_java_callback_method;
static volatile sig_atomic_t g_java_ready;
/** exec 前需要关闭的文件描述符上限，在初始化时计算。 */
static unsigned int g_max_fd = 65536u;
#ifdef APM_NATIVE_TEST
/** 仅测试看护者能否回收已暂停崩溃线程的辅助进程。 */
static volatile sig_atomic_t g_test_hang_dumper;
/** 模拟 Java 补充超过截止时间，不在测试子进程内调用 JVM。 */
static volatile sig_atomic_t g_test_hang_callback;
/** 仅设备测试使用的受控信号和合成 abort 消息。 */
static int g_test_signal = SIGSEGV;
static char g_test_abort_message[256];
#endif

_Static_assert(__atomic_always_lock_free(sizeof(sig_atomic_t), 0), "signal state must be lock-free");

/** 将固定宽度整数写入小端记录，不调用格式化或分配函数。 */
static void apm_write_u32(uint8_t *target, uint32_t value) {
    for (unsigned int index = 0; index < 4; index++) target[index] = (uint8_t) (value >> (index * 8));
}

/** 将 64 位数值写成跨 ABI 一致的小端字节。 */
static void apm_write_u64(uint8_t *target, uint64_t value) {
    for (unsigned int index = 0; index < 8; index++) target[index] = (uint8_t) (value >> (index * 8));
}

/** 对有限核心记录计算 FNV-1a 校验值，检测尾部半写。 */
static uint32_t apm_core_checksum(const uint8_t *data, size_t length) {
    uint32_t checksum = 2166136261u;
    for (size_t index = 0; index < length; index++) checksum = (checksum ^ data[index]) * 16777619u;
    return checksum;
}

/** 使用信号安全的 write 循环完成有限记录；失败时不宣称提交成功。 */
static int apm_write_all(int fd, const uint8_t *data, size_t length) {
    size_t written = 0;
    while (written < length) {
        ssize_t count = write(fd, data + written, length - written);
        if (count > 0) written += (size_t) count;
        else if (count < 0 && errno == EINTR) continue;
        else return -1;
    }
    return 0;
}

/** 只将信号、线程身份和寄存器数值写入核心，不持久化 ucontext 或内存转储。 */
static int apm_commit_core(int signal_number, const siginfo_t *info, ucontext_t *context, apm_trace_request *request) {
    uint8_t bytes[APM_CORE_BUFFER_BYTES] = {0};
    struct timespec occurred = {0};
    xcd_regs_t registers = {0};
    xcd_regs_label_t *labels = NULL;
    size_t label_count = 0;
    size_t cursor = 56;
    if (context != NULL) {
        xcd_regs_load_from_ucontext(&registers, context);
        xcd_regs_get_labels(&labels, &label_count);
    }
    if (label_count > APM_MAX_REGISTERS) label_count = APM_MAX_REGISTERS;
    (void) clock_gettime(CLOCK_REALTIME, &occurred);
    bytes[0] = 'A'; bytes[1] = 'N'; bytes[2] = 'C'; bytes[3] = 'R';
    apm_write_u32(bytes + 4, APM_CORE_VERSION);
    apm_write_u32(bytes + 8, (uint32_t) (56 + label_count * 16 + 4));
    uint64_t timestamp = (uint64_t) occurred.tv_sec * 1000u + (uint64_t) occurred.tv_nsec / 1000000u;
    pid_t pid = getpid();
    pid_t tid = (pid_t) syscall(SYS_gettid);
    apm_write_u64(bytes + 12, timestamp);
    apm_write_u32(bytes + 20, (uint32_t) pid);
    apm_write_u32(bytes + 24, (uint32_t) tid);
    apm_write_u32(bytes + 28, (uint32_t) signal_number);
    apm_write_u32(bytes + 32, (uint32_t) (info == NULL ? 0 : info->si_code));
    // 用户发送的信号没有可靠故障地址；同步故障的零地址必须保留。
    int has_fault = info != NULL && info->si_code > 0 && signal_number != SIGABRT;
    apm_write_u32(bytes + 36, (uint32_t) has_fault);
    apm_write_u64(bytes + 40, has_fault ? (uint64_t) (uintptr_t) info->si_addr : 0);
    apm_write_u32(bytes + 48, (uint32_t) label_count);
    apm_write_u32(bytes + 52, APM_ABI_ID);
    for (size_t index = 0; index < label_count; index++) {
        const char *name = labels[index].name;
        for (size_t position = 0; position < 8 && name[position] != '\0'; position++) {
            bytes[cursor + position] = (uint8_t) name[position];
        }
        apm_write_u64(bytes + cursor + 8, (uint64_t) registers.r[labels[index].idx]);
        cursor += 16;
    }
    apm_write_u32(bytes + cursor, apm_core_checksum(bytes, cursor));
    if (g_core_fd < 0 || apm_write_all(g_core_fd, bytes, cursor + 4) != 0 || fsync(g_core_fd) != 0) return -1;
    request->magic = APM_TRACE_REQUEST_MAGIC;
    request->version = APM_TRACE_VERSION;
    request->pid = pid;
    request->tid = tid;
    request->watchdog_tid = (pid_t) __atomic_load_n(&g_worker_tid, __ATOMIC_ACQUIRE);
    request->signal_number = signal_number;
    request->occurred_at = timestamp;
    request->registers = registers;
    return context == NULL ? -1 : 0;
}

/** 在正常工作者上下文等待独立进程，超时后终止并回收。 */
static void apm_reap_dumper(pid_t child) {
    int status = 0;
    for (unsigned int attempt = 0; attempt < APM_DUMPER_TIMEOUT_MILLIS / 10u; attempt++) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            // 普通看护线程只记录退出类别，不输出路径、现场或凭据。
            int code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
            int signal_number = WIFSIGNALED(status) ? WTERMSIG(status) : 0;
            (void) __android_log_print(code == 0 && signal_number == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
                                       "ApmNativeCrash", "dumper exited: code=%d signal=%d", code, signal_number);
            return;
        }
        if (result < 0 && errno == ECHILD) return;
        if (result < 0 && errno != EINTR) break;
        (void) poll(NULL, 0, 10);
    }
    (void) __android_log_write(ANDROID_LOG_WARN, "ApmNativeCrash", "dumper deadline exceeded");
    (void) kill(child, SIGKILL);
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) { }
}

/** 唯一工作者负责超时回收；它不应被 dumper 暂停。 */
static void *apm_watch_dumper(void *unused) {
    (void) unused;
    JNIEnv *env = NULL;
    if (g_java_vm != NULL && g_java_callback != NULL && g_java_callback_method != NULL &&
        (*g_java_vm)->AttachCurrentThread(g_java_vm, &env, NULL) == JNI_OK) {
        __atomic_store_n(&g_java_ready, 1, __ATOMIC_RELEASE);
    }
#ifdef APM_NATIVE_TEST
    if (g_test_hang_callback) __atomic_store_n(&g_java_ready, 1, __ATOMIC_RELEASE);
#endif
    __atomic_store_n(&g_worker_tid, (sig_atomic_t) syscall(SYS_gettid), __ATOMIC_RELEASE);
    const char ready = 'R';
    (void) write(g_done_fds[1], &ready, 1);
    for (;;) {
        struct pollfd descriptor = {.fd = g_worker_fds[0], .events = POLLIN};
        int available = poll(&descriptor, 1, -1);
        if (available < 0 && errno == EINTR) continue;
        if (available <= 0 || (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) break;
        apm_worker_request request = {0};
        ssize_t count = read(g_worker_fds[0], &request, sizeof(request));
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count != sizeof(request) || request.child <= 0) break;
        apm_reap_dumper(request.child);
        const char done = 'D';
        (void) write(g_done_fds[1], &done, 1);
#ifdef APM_NATIVE_TEST
        if (g_test_hang_callback) {
            (void) poll(NULL, 0, 3500);
            const char late = 'J';
            (void) write(g_done_fds[1], &late, 1);
        } else
#endif
        if (env != NULL) {
            struct timespec now = {0};
            (void) clock_gettime(CLOCK_MONOTONIC, &now);
            jlong deadline = (jlong) now.tv_sec * 1000000000LL + now.tv_nsec + APM_CALLBACK_TIMEOUT_NANOS;
            jboolean accepted = (*env)->CallBooleanMethod(env, g_java_callback, g_java_callback_method,
                                                         deadline, (jint) request.pid, (jint) request.tid,
                                                         (jlong) request.occurred_at);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                accepted = JNI_FALSE;
            }
            const char callback_done = accepted == JNI_TRUE ? 'J' : 'F';
            (void) write(g_done_fds[1], &callback_done, 1);
        }
    }
    __atomic_store_n(&g_java_ready, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&g_worker_tid, 0, __ATOMIC_RELEASE);
    if (env != NULL) (void) (*g_java_vm)->DetachCurrentThread(g_java_vm);
    return NULL;
}

/** 安装时关闭已创建通道并回收尚未启动采集的工作者。 */
static void apm_close_dumper_resources(void) {
    if (g_worker_started) {
        apm_worker_request stop = {0};
        (void) write(g_worker_fds[1], &stop, sizeof(stop));
        (void) close(g_worker_fds[1]);
        g_worker_fds[1] = -1;
        (void) pthread_join(g_worker, NULL);
        g_worker_started = 0;
    }
    for (size_t index = 0; index < 2; index++) {
        if (g_request_fds[index] >= 0) (void) close(g_request_fds[index]);
        if (g_worker_fds[index] >= 0) (void) close(g_worker_fds[index]);
        if (g_done_fds[index] >= 0) (void) close(g_done_fds[index]);
        g_request_fds[index] = g_worker_fds[index] = g_done_fds[index] = -1;
    }
    if (g_trace_fd >= 0) (void) close(g_trace_fd);
    if (g_diagnostics_fd >= 0) (void) close(g_diagnostics_fd);
    if (g_null_fd >= 0) (void) close(g_null_fd);
    g_trace_fd = g_diagnostics_fd = g_null_fd = -1;
    g_dumper_path[0] = '\0';
}

/** 确保 exec 前的源描述符不占用标准输入、输出或错误位置。 */
static int apm_keep_above_stdio(int *descriptor) {
    if (*descriptor >= 4) return 0;
    int replacement = fcntl(*descriptor, F_DUPFD_CLOEXEC, 4);
    if (replacement < 0) return -1;
    (void) close(*descriptor);
    *descriptor = replacement;
    return 0;
}

/** 在信号监听前准备文件、管道和可回收工作者。 */
static int apm_prepare_dumper(const char *dumper_path, const char *trace_path, const char *diagnostics_path) {
    if (dumper_path == NULL || trace_path == NULL || diagnostics_path == NULL || strlen(dumper_path) >= sizeof(g_dumper_path)) return -1;
    int trace_created = 0;
    int diagnostics_created = 0;
    memcpy(g_dumper_path, dumper_path, strlen(dumper_path) + 1);
    g_trace_fd = open(trace_path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (g_trace_fd < 0) goto fail;
    trace_created = 1;
    if (apm_keep_above_stdio(&g_trace_fd) != 0) goto fail;
    g_diagnostics_fd = open(diagnostics_path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (g_diagnostics_fd < 0) goto fail;
    diagnostics_created = 1;
    if (apm_keep_above_stdio(&g_diagnostics_fd) != 0) goto fail;
    g_null_fd = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (g_null_fd < 0 || apm_keep_above_stdio(&g_null_fd) != 0) goto fail;
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, g_request_fds) != 0 ||
        apm_keep_above_stdio(&g_request_fds[0]) != 0 || apm_keep_above_stdio(&g_request_fds[1]) != 0 ||
        pipe2(g_worker_fds, O_CLOEXEC | O_NONBLOCK) != 0 || apm_keep_above_stdio(&g_worker_fds[0]) != 0 ||
        apm_keep_above_stdio(&g_worker_fds[1]) != 0 || pipe2(g_done_fds, O_CLOEXEC | O_NONBLOCK) != 0 ||
        apm_keep_above_stdio(&g_done_fds[0]) != 0 || apm_keep_above_stdio(&g_done_fds[1]) != 0) goto fail;
    struct rlimit limit;
    if (getrlimit(RLIMIT_NOFILE, &limit) == 0 && limit.rlim_cur != RLIM_INFINITY && limit.rlim_cur <= UINT_MAX) {
        g_max_fd = (unsigned int) limit.rlim_cur;
    }
    if (pthread_create(&g_worker, NULL, apm_watch_dumper, NULL) != 0) goto fail;
    g_worker_started = 1;
    struct pollfd ready = {.fd = g_done_fds[0], .events = POLLIN};
    int result;
    do { result = poll(&ready, 1, 2000); } while (result < 0 && errno == EINTR);
    char marker = 0;
    if (result <= 0 || read(g_done_fds[0], &marker, 1) != 1 || marker != 'R') goto fail;
    return 0;
fail:
    apm_close_dumper_resources();
    if (trace_created) (void) unlink(trace_path);
    if (diagnostics_created) (void) unlink(diagnostics_path);
    return -1;
}

/** clone 后只使用信号安全系统调用，随后立即 exec 安装目录中的 dumper。 */
static void apm_exec_dumper_child(pid_t expected_parent) {
    // 原进程被系统直接终止时同步终止辅助进程，避免重启导入后仍有迟到写入。
    if (syscall(SYS_prctl, PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) != 0 || getppid() != expected_parent) _exit(125);
    // clone 子进程继承宿主的 fdsan 所有权标签；只关闭自己的副本，不能经 libc 误判为宿主重复关闭。
    if (syscall(SYS_dup3, g_request_fds[1], STDIN_FILENO, 0) < 0 ||
        syscall(SYS_dup3, g_trace_fd, STDOUT_FILENO, 0) < 0 ||
        syscall(SYS_dup3, g_null_fd, STDERR_FILENO, 0) < 0 ||
        syscall(SYS_dup3, g_diagnostics_fd, 3, 0) < 0) _exit(126);
    // 旧 Android 的 seccomp 可能对较新 close_range 直接发送 SIGSYS，不能依赖 ENOSYS 回退。
    for (unsigned int descriptor = 4; descriptor < g_max_fd; descriptor++) (void) syscall(SYS_close, (int) descriptor);
#ifdef APM_NATIVE_TEST
    if (g_test_hang_dumper) {
        apm_trace_request request;
        size_t received = 0;
        while (received < sizeof(request)) {
            ssize_t count = read(STDIN_FILENO, (uint8_t *) &request + received, sizeof(request) - received);
            if (count > 0) received += (size_t) count;
            else if (count < 0 && errno == EINTR) continue;
            else _exit(123);
        }
        if (syscall(SYS_ptrace, PTRACE_ATTACH, request.tid, 0, 0) != 0) _exit(124);
        int status = 0;
        if (syscall(SYS_wait4, request.tid, &status, __WALL, NULL) < 0) _exit(124);
        for (;;) {
            struct timespec interval = {.tv_sec = 1, .tv_nsec = 0};
            (void) syscall(SYS_nanosleep, &interval, NULL);
        }
    }
#endif
    char *const arguments[] = {g_dumper_path, "--capture", NULL};
    char *const environment[] = {NULL};
    (void) execve(g_dumper_path, arguments, environment);
    _exit(127);
}

/** 最小核心已提交后启动有截止时间的独立回溯，不在信号上下文使用锁或 Java。 */
static int apm_start_dumper(const apm_trace_request *request) {
    if (!g_worker_started || request->watchdog_tid <= 0 || request->tid == request->watchdog_tid || g_trace_fd < 0) return 0;
    long original_dumpable = syscall(SYS_prctl, PR_GET_DUMPABLE, 0, 0, 0, 0);
    if (original_dumpable < 0 || syscall(SYS_prctl, PR_SET_DUMPABLE, 1, 0, 0, 0) != 0) return 0;
    int accepted = 0;
    pid_t child = (pid_t) syscall(SYS_clone, (unsigned long) SIGCHLD, 0, 0, 0, 0);
    if (child == 0) apm_exec_dumper_child(request->pid);
    if (child > 0) {
#ifdef PR_SET_PTRACER
        int ptracer_set = syscall(SYS_prctl, PR_SET_PTRACER, child, 0, 0, 0) == 0;
#endif
        apm_worker_request command = {.child = child, .pid = request->pid, .tid = request->tid,
                                      .occurred_at = request->occurred_at};
        ssize_t notified = write(g_worker_fds[1], &command, sizeof(command));
        if (notified != sizeof(command)) {
            (void) kill(child, SIGKILL);
        } else {
            ssize_t sent = send(g_request_fds[0], request, sizeof(*request), MSG_NOSIGNAL | MSG_DONTWAIT);
            if (sent != sizeof(*request)) (void) kill(child, SIGKILL);
            unsigned int callback_started = APM_HANDLER_TIMEOUT_MILLIS / 10u;
            for (unsigned int attempt = 0; attempt < APM_HANDLER_TIMEOUT_MILLIS / 10u; attempt++) {
                if (callback_started < APM_HANDLER_TIMEOUT_MILLIS / 10u && attempt - callback_started >= 200u) break;
                char marker;
                if (read(g_done_fds[0], &marker, 1) == 1) {
                    if (marker == 'D' && !__atomic_load_n(&g_java_ready, __ATOMIC_ACQUIRE)) break;
                    if (marker == 'D') callback_started = attempt;
                    if (marker == 'J' || marker == 'F') { accepted = marker == 'J'; break; }
                }
                struct timespec interval = {.tv_sec = 0, .tv_nsec = 10000000};
                (void) syscall(SYS_nanosleep, &interval, NULL);
                if (attempt + 1 == APM_HANDLER_TIMEOUT_MILLIS / 10u) (void) kill(child, SIGKILL);
            }
        }
#ifdef PR_SET_PTRACER
        if (ptracer_set) (void) syscall(SYS_prctl, PR_SET_PTRACER, 0, 0, 0, 0);
#endif
    }
    (void) syscall(SYS_prctl, PR_SET_DUMPABLE, original_dumpable, 0, 0, 0);
    return accepted;
}

/** 以完整性校验固定 Java 补充接纳结果，迟到写入不能改变事件。 */
static void apm_commit_decision(const apm_trace_request *request, int accepted) {
    if (g_decision_fd < 0) return;
    uint8_t bytes[36] = {'A', 'N', 'D', 'C'};
    apm_write_u32(bytes + 4, 1);
    apm_write_u64(bytes + 8, request->occurred_at);
    apm_write_u32(bytes + 16, (uint32_t) request->pid);
    apm_write_u32(bytes + 20, (uint32_t) request->tid);
    apm_write_u32(bytes + 24, accepted ? 1u : 0u);
    apm_write_u32(bytes + 28, sizeof(bytes));
    apm_write_u32(bytes + 32, apm_core_checksum(bytes, 32));
    if (apm_write_all(g_decision_fd, bytes, sizeof(bytes)) == 0) (void) fsync(g_decision_fd);
}

/** 查找安装时记录的原始信号处理器。 */
static apm_signal_slot *apm_signal_slot_for(int signal_number) {
    for (size_t index = 0; index < sizeof(g_signals) / sizeof(g_signals[0]); index++) {
        if (g_signals[index].number == signal_number) return &g_signals[index];
    }
    return NULL;
}

/** 判断当前信号处理权是否仍由本模块持有。 */
static int apm_is_our_handler(int signal_number, void (*handler)(int, siginfo_t *, void *)) {
    struct sigaction current;
    return sigaction(signal_number, NULL, &current) == 0
            && (current.sa_flags & SA_SIGINFO) != 0 && current.sa_sigaction == handler;
}

/** 先保存有限核心，再恢复原处理器并交还系统崩溃流程。 */
static void apm_signal_handler(int signal_number, siginfo_t *info, void *context) {
    sig_atomic_t expected = 1;
    if (__atomic_compare_exchange_n(&g_state, &expected, 2, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        apm_trace_request request = {0};
        if (apm_commit_core(signal_number, info, (ucontext_t *) context, &request) == 0) {
            int accepted = apm_start_dumper(&request);
            apm_commit_decision(&request, accepted);
        }
    }
    apm_signal_slot *slot = apm_signal_slot_for(signal_number);
    // 第三方后来接管并主动调用本处理器时，不能覆盖第三方当前处理权。
    if (slot != NULL && slot->installed && apm_is_our_handler(signal_number, apm_signal_handler)) {
        (void) sigaction(signal_number, &slot->previous, NULL);
        // 同步故障在返回后重新触发；用户信号和 abort 必须重新排队。
        if (signal_number == SIGABRT || info == NULL || info->si_code <= 0) {
            (void) syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), signal_number);
        }
    }
}

/** 在原拥有线程恢复备用栈后释放其内存。 */
static int apm_release_alt_stack(void) {
    if (g_alt_stack == NULL) return 0;
    if ((pid_t) syscall(SYS_gettid) != g_alt_owner) return 2;
    if (sigaltstack(&g_previous_alt_stack, NULL) != 0) return 3;
    (void) munmap(g_alt_stack, APM_ALT_STACK_BYTES);
    g_alt_stack = NULL;
    g_alt_owner = 0;
    return 0;
}

/** 返回内部原生记录格式的版本；探测不会启动监听。 */
JNIEXPORT jint JNICALL
Java_com_shanshui_performance_crash_NativeCrashBridge_nativeProtocolVersion(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    return APM_CORE_VERSION;
}

/** 普通 Java 线程可检查本进程是否已进入 Native 信号采集。 */
JNIEXPORT jboolean JNICALL
Java_com_shanshui_performance_crash_NativeCrashBridge_nativeCaptureInProgress(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    return __atomic_load_n(&g_state, __ATOMIC_ACQUIRE) == 2 ? JNI_TRUE : JNI_FALSE;
}

/** 预打开核心文件并安装信号处理器；失败时回滚本次资源。 */
JNIEXPORT jint JNICALL
Java_com_shanshui_performance_crash_NativeCrashBridge_nativeInstall(JNIEnv *env, jobject receiver,
                                                                    jstring core_path, jboolean use_alt_stack,
                                                                    jstring dumper_path, jstring trace_path,
                                                                    jstring diagnostics_path, jstring decision_path,
                                                                    jboolean use_callback) {
    if (core_path == NULL || ((dumper_path == NULL) != (trace_path == NULL)) ||
        ((dumper_path == NULL) != (diagnostics_path == NULL)) ||
        (use_callback && (dumper_path == NULL || decision_path == NULL))) return 1;
    const char *path = (*env)->GetStringUTFChars(env, core_path, NULL);
    if (path == NULL) return 1;
    const char *dumper = dumper_path == NULL ? NULL : (*env)->GetStringUTFChars(env, dumper_path, NULL);
    const char *trace = trace_path == NULL ? NULL : (*env)->GetStringUTFChars(env, trace_path, NULL);
    const char *diagnostics = diagnostics_path == NULL ? NULL : (*env)->GetStringUTFChars(env, diagnostics_path, NULL);
    const char *decision = decision_path == NULL ? NULL : (*env)->GetStringUTFChars(env, decision_path, NULL);
    if ((dumper_path != NULL && dumper == NULL) || (trace_path != NULL && trace == NULL) ||
        (diagnostics_path != NULL && diagnostics == NULL) || (decision_path != NULL && decision == NULL)) {
        if (dumper != NULL) (*env)->ReleaseStringUTFChars(env, dumper_path, dumper);
        if (trace != NULL) (*env)->ReleaseStringUTFChars(env, trace_path, trace);
        if (diagnostics != NULL) (*env)->ReleaseStringUTFChars(env, diagnostics_path, diagnostics);
        if (decision != NULL) (*env)->ReleaseStringUTFChars(env, decision_path, decision);
        (*env)->ReleaseStringUTFChars(env, core_path, path);
        return 1;
    }
    (void) pthread_mutex_lock(&g_lifecycle_lock);
    int result = 1;
    int decision_created = 0;
    if (__atomic_load_n(&g_state, __ATOMIC_ACQUIRE) != 0 || g_alt_stack != NULL) goto done;
    g_core_fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (g_core_fd < 0) goto done;
    if (use_callback) {
        g_decision_fd = open(decision, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
        if (g_decision_fd < 0) goto rollback;
        decision_created = 1;
        jclass clazz = (*env)->GetObjectClass(env, receiver);
        if (clazz != NULL && (*env)->GetJavaVM(env, &g_java_vm) == JNI_OK) {
            g_java_callback_method = (*env)->GetMethodID(env, clazz, "onNativeCaptureCompleted", "(JIIJ)Z");
            if (g_java_callback_method != NULL) g_java_callback = (*env)->NewGlobalRef(env, receiver);
        }
        if (clazz != NULL) (*env)->DeleteLocalRef(env, clazz);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (g_java_callback == NULL) { g_java_vm = NULL; g_java_callback_method = NULL; }
    }
    if (dumper != NULL && (access(dumper, X_OK) != 0 || apm_prepare_dumper(dumper, trace, diagnostics) != 0)) goto rollback;
    if (use_alt_stack) {
        if (sigaltstack(NULL, &g_previous_alt_stack) != 0 || (g_previous_alt_stack.ss_flags & SS_ONSTACK) != 0) goto rollback;
        g_alt_stack = mmap(NULL, APM_ALT_STACK_BYTES, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (g_alt_stack == MAP_FAILED) { g_alt_stack = NULL; goto rollback; }
        stack_t next = {.ss_sp = g_alt_stack, .ss_size = APM_ALT_STACK_BYTES, .ss_flags = 0};
        if (sigaltstack(&next, NULL) != 0) { (void) munmap(g_alt_stack, APM_ALT_STACK_BYTES); g_alt_stack = NULL; goto rollback; }
        g_alt_owner = (pid_t) syscall(SYS_gettid);
    }
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigfillset(&action.sa_mask);
    action.sa_sigaction = apm_signal_handler;
    action.sa_flags = SA_SIGINFO | SA_RESTART | (use_alt_stack ? SA_ONSTACK : 0);
    for (size_t index = 0; index < sizeof(g_signals) / sizeof(g_signals[0]); index++) {
        if (sigaction(g_signals[index].number, &action, &g_signals[index].previous) != 0) goto rollback;
        g_signals[index].installed = 1;
    }
    __atomic_store_n(&g_state, 1, __ATOMIC_RELEASE);
    result = 0;
    goto done;

rollback:
    for (size_t index = 0; index < sizeof(g_signals) / sizeof(g_signals[0]); index++) {
        if (g_signals[index].installed && apm_is_our_handler(g_signals[index].number, apm_signal_handler)) {
            (void) sigaction(g_signals[index].number, &g_signals[index].previous, NULL);
        }
        g_signals[index].installed = 0;
    }
    (void) apm_release_alt_stack();
    if (g_worker_started) {
        apm_close_dumper_resources();
        (void) unlink(trace);
        (void) unlink(diagnostics);
    }
    if (g_decision_fd >= 0) { (void) close(g_decision_fd); g_decision_fd = -1; }
    if (decision_created) (void) unlink(decision);
    if (g_java_callback != NULL) (*env)->DeleteGlobalRef(env, g_java_callback);
    g_java_callback = NULL; g_java_callback_method = NULL; g_java_vm = NULL;
    (void) close(g_core_fd);
    g_core_fd = -1;
    (void) unlink(path);
done:
    (void) pthread_mutex_unlock(&g_lifecycle_lock);
    if (dumper != NULL) (*env)->ReleaseStringUTFChars(env, dumper_path, dumper);
    if (trace != NULL) (*env)->ReleaseStringUTFChars(env, trace_path, trace);
    if (diagnostics != NULL) (*env)->ReleaseStringUTFChars(env, diagnostics_path, diagnostics);
    if (decision != NULL) (*env)->ReleaseStringUTFChars(env, decision_path, decision);
    (*env)->ReleaseStringUTFChars(env, core_path, path);
    return result;
}

/** 只恢复仍属于本模块的信号处理器，不覆盖后来注册的第三方。 */
JNIEXPORT jint JNICALL
Java_com_shanshui_performance_crash_NativeCrashBridge_nativeUninstall(JNIEnv *env, jobject receiver) {
    (void) receiver;
    (void) pthread_mutex_lock(&g_lifecycle_lock);
    sig_atomic_t expected = 1;
    if (!__atomic_compare_exchange_n(&g_state, &expected, 0, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE) && expected == 2) {
        (void) pthread_mutex_unlock(&g_lifecycle_lock);
        return 3;
    }
    for (size_t index = 0; index < sizeof(g_signals) / sizeof(g_signals[0]); index++) {
        if (g_signals[index].installed && apm_is_our_handler(g_signals[index].number, apm_signal_handler)) {
            (void) sigaction(g_signals[index].number, &g_signals[index].previous, NULL);
        }
        g_signals[index].installed = 0;
    }
    if (g_core_fd >= 0) { (void) close(g_core_fd); g_core_fd = -1; }
    apm_close_dumper_resources();
    if (g_decision_fd >= 0) { (void) close(g_decision_fd); g_decision_fd = -1; }
    if (g_java_callback != NULL) (*env)->DeleteGlobalRef(env, g_java_callback);
    g_java_callback = NULL; g_java_callback_method = NULL; g_java_vm = NULL;
    int result = apm_release_alt_stack();
    (void) pthread_mutex_unlock(&g_lifecycle_lock);
    return result == 3 ? 4 : result;
}

/** 当关闭发生在其他线程时，允许原安装线程完成备用栈清理。 */
JNIEXPORT jint JNICALL
Java_com_shanshui_performance_crash_NativeCrashBridge_nativeReleaseAltStack(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    (void) pthread_mutex_lock(&g_lifecycle_lock);
    int result = __atomic_load_n(&g_state, __ATOMIC_ACQUIRE) == 0 ? apm_release_alt_stack() : 1;
    (void) pthread_mutex_unlock(&g_lifecycle_lock);
    return result;
}

#ifdef APM_NATIVE_TEST
/** 在 Debug 测试中保存安装前处理器，避免测试污染宿主。 */
static struct sigaction g_test_original_handler;
static int g_test_handler_saved;

/** 模拟在本模块之后注册的第三方信号处理器。 */
static void apm_test_third_party_handler(int number, siginfo_t *info, void *context) {
    (void) number;
    (void) info;
    (void) context;
}

/** 保存测试前 SIGSEGV 处理权。 */
JNIEXPORT jboolean JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeRememberHandler(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    g_test_handler_saved = sigaction(SIGSEGV, NULL, &g_test_original_handler) == 0;
    return g_test_handler_saved ? JNI_TRUE : JNI_FALSE;
}

/** 模拟第三方后来接管 SIGSEGV。 */
JNIEXPORT jboolean JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeInstallThirdPartyHandler(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    struct sigaction action = {0};
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = apm_test_third_party_handler;
    action.sa_flags = SA_SIGINFO;
    return sigaction(SIGSEGV, &action, NULL) == 0 ? JNI_TRUE : JNI_FALSE;
}

/** 判断第三方处理权是否仍在。 */
JNIEXPORT jboolean JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeHasThirdPartyHandler(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    return apm_is_our_handler(SIGSEGV, apm_test_third_party_handler) ? JNI_TRUE : JNI_FALSE;
}

/** 测试结束后恢复测试前的处理权。 */
JNIEXPORT void JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeRestoreRememberedHandler(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    if (g_test_handler_saved) (void) sigaction(SIGSEGV, &g_test_original_handler, NULL);
    g_test_handler_saved = 0;
}

/** 测试进程内触发同步空指针错误或 SIGABRT。 */
static void apm_test_crash(int signal_number) {
    if (signal_number == SIGABRT) {
        (void) raise(SIGABRT);
    } else {
        volatile int *address = (volatile int *) (uintptr_t) 0;
        *address = 1;
    }
}

/** 仅用于测试不附着 JVM 的线程。 */
static void *apm_test_native_worker(void *unused) {
    (void) unused;
    (void) pthread_setname_np(pthread_self(), "native-probe");
    apm_test_crash(g_test_signal);
    return NULL;
}

/** 设置隔离子进程的测试信号和合成消息，不影响正式构建。 */
JNIEXPORT void JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeConfigureSignal(JNIEnv *env, jobject receiver,
                                                                           jint signal_number, jstring message) {
    (void) receiver;
    g_test_signal = signal_number;
    g_test_abort_message[0] = '\0';
    if (message == NULL) return;
    const char *text = (*env)->GetStringUTFChars(env, message, NULL);
    if (text == NULL) return;
    size_t length = strnlen(text, sizeof(g_test_abort_message));
    if (length < sizeof(g_test_abort_message)) memcpy(g_test_abort_message, text, length + 1);
    (*env)->ReleaseStringUTFChars(env, message, text);
}

/** 测试独立进程内的主线程或 Java 创建的后台线程。 */
JNIEXPORT void JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeCrashInCurrentThread(JNIEnv *env, jobject receiver, jint signal_number) {
    (void) env;
    (void) receiver;
    apm_test_crash(signal_number);
}

/** 测试完全不附着 JVM 的纯 Native 线程。 */
JNIEXPORT void JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeCrashInNativeThread(JNIEnv *env, jobject receiver) {
    (void) env;
    (void) receiver;
    pthread_t thread;
    if (pthread_create(&thread, NULL, apm_test_native_worker, NULL) == 0) (void) pthread_join(thread, NULL);
}

/** 测试专用：在隔离子进程准备看护者，再用真实信号运行安装目录中的 dumper。 */
JNIEXPORT jint JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeForkDumperCrash(JNIEnv *env, jobject receiver,
                                                                            jstring dumper_path, jstring trace_path,
                                                                            jboolean native_thread, jboolean hang_dumper,
                                                                            jboolean hang_callback) {
    (void) receiver;
    if (dumper_path == NULL || trace_path == NULL) return -1;
    const char *dumper = (*env)->GetStringUTFChars(env, dumper_path, NULL);
    const char *trace = (*env)->GetStringUTFChars(env, trace_path, NULL);
    if (dumper == NULL || trace == NULL) {
        if (dumper != NULL) (*env)->ReleaseStringUTFChars(env, dumper_path, dumper);
        if (trace != NULL) (*env)->ReleaseStringUTFChars(env, trace_path, trace);
        return -2;
    }
    char dumper_copy[PATH_MAX];
    char trace_copy[PATH_MAX];
    char diagnostics_copy[PATH_MAX];
    char decision_copy[PATH_MAX];
    size_t dumper_length = strnlen(dumper, sizeof(dumper_copy));
    size_t trace_length = strnlen(trace, sizeof(trace_copy));
    if (dumper_length < sizeof(dumper_copy)) memcpy(dumper_copy, dumper, dumper_length + 1);
    if (trace_length < sizeof(trace_copy)) memcpy(trace_copy, trace, trace_length + 1);
    (*env)->ReleaseStringUTFChars(env, dumper_path, dumper);
    (*env)->ReleaseStringUTFChars(env, trace_path, trace);
    if (dumper_length >= sizeof(dumper_copy) || trace_length >= sizeof(trace_copy)) return -3;
    int diagnostics_length = snprintf(diagnostics_copy, sizeof(diagnostics_copy), "%s.diagnostics", trace_copy);
    if (diagnostics_length <= 0 || (size_t) diagnostics_length >= sizeof(diagnostics_copy)) return -3;
    int decision_length = snprintf(decision_copy, sizeof(decision_copy), "%s.decision", trace_copy);
    if (decision_length <= 0 || (size_t) decision_length >= sizeof(decision_copy)) return -3;
    pid_t child = fork();
    if (child < 0) return -4;
    if (child == 0) {
        g_test_hang_dumper = hang_dumper;
        g_test_hang_callback = hang_callback;
        if (hang_callback) {
            g_decision_fd = open(decision_copy, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
            if (g_decision_fd < 0) _exit(119);
        }
        if (apm_prepare_dumper(dumper_copy, trace_copy, diagnostics_copy) != 0) _exit(120);
        if (g_test_signal == SIGABRT && g_test_abort_message[0] != '\0') {
            void (*set_abort_message)(const char *) = dlsym(RTLD_DEFAULT, "android_set_abort_message");
            if (set_abort_message == NULL) _exit(118);
            set_abort_message(g_test_abort_message);
        }
        // 仅 Debug 测试进程写入合成文本，用于核验原生首次落盘前的脱敏。
        (void) __android_log_write(ANDROID_LOG_INFO, "ApmNativeTest", "safe-native-log alpha");
        (void) __android_log_write(ANDROID_LOG_INFO, "ApmNativeTest", "token=synthetic-secret mail=test@example.test phone=138-0013-8000 path=/data/user/0/private");
        (void) __android_log_write(ANDROID_LOG_INFO, "ApmNativeTest", "X-App-Key: apm_ak_AbCdefGHijkLmnoPQRstuVwXYZ");
        (void) __android_log_write(ANDROID_LOG_INFO, "ApmNativeTest", "0000007fe0ef1ffa  0000007be0ef226f");
        (void) __android_log_write(ANDROID_LOG_INFO, "ApmNativeTest", "dump 1234abcd1234abcd 5678abcd5678abcd 9876abcd9876abcd");
        (void) __android_log_write(ANDROID_LOG_INFO, "ApmNativeTest", "map 1234abcd1234abcd-5678abcd5678abcd r--p");
        if (native_thread) {
            pthread_t thread;
            if (pthread_create(&thread, NULL, apm_test_native_worker, NULL) != 0) _exit(121);
            (void) pthread_join(thread, NULL);
        } else {
            apm_test_crash(g_test_signal);
        }
        _exit(122);
    }
    int status = 0;
    for (int attempt = 0; attempt < 150; attempt++) {
        pid_t waited = waitpid(child, &status, WNOHANG);
        if (waited == child) return WIFSIGNALED(status) ? WTERMSIG(status) : -WEXITSTATUS(status);
        if (waited < 0 && errno != EINTR) return -5;
        (void) usleep(100000);
    }
    (void) kill(child, SIGKILL);
    (void) waitpid(child, NULL, 0);
    return -6;
}

/** 仅在 Debug 测试构建中令隔离子进程产生真正的致命信号。 */
JNIEXPORT jint JNICALL
Java_com_shanshui_performance_crash_NativeSignalProbe_nativeForkCrash(JNIEnv *env, jobject receiver, jint signal_number) {
    (void) env;
    (void) receiver;
    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        if (signal_number == SIGSEGV) {
            volatile int *address = (volatile int *) (uintptr_t) 0;
            *address = 1;
        } else if (signal_number == SIGABRT) {
            (void) raise(SIGABRT);
        }
        _exit(99);
    }
    int status = 0;
    for (int attempt = 0; attempt < 100; attempt++) {
        pid_t waited = waitpid(child, &status, WNOHANG);
        if (waited == child) return WIFSIGNALED(status) ? WTERMSIG(status) : -WEXITSTATUS(status);
        if (waited < 0 && errno != EINTR) return -2;
        (void) usleep(100000);
    }
    (void) kill(child, SIGKILL);
    (void) waitpid(child, NULL, 0);
    return -3;
}
#endif
