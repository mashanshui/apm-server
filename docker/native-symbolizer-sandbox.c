/* 限制 LLVM 进程只能读取符号目录；动态依赖在构造函数之前由系统加载。 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/landlock.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

/** Landlock 不可用时中止工具，避免降级为无文件隔离的处理。 */
static void sandbox_failure(void) {
    /* 错误仅说明阶段，不回显不可信路径或原始工具输出。 */
    static const char message[] = "native symbolizer sandbox unavailable\n";
    /* 忽略日志输出故障，仍必须以失败状态退出。 */
    ssize_t written = write(STDERR_FILENO, message, sizeof(message) - 1);
    (void)written;
    _exit(125);
}

/** 在 LLVM 主程序开始前将文件读取限制到受控符号目录。 */
__attribute__((constructor)) static void restrict_symbolizer_files(void) {
    /* 目录由服务端符号卷配置提供；必须是明确的绝对目录。 */
    const char *directory = getenv("APM_SYMBOL_DIRECTORY");
    if (directory == NULL || directory[0] != '/' || directory[1] == '\0') sandbox_failure();
    /* 使用 Landlock ABI 1 即支持的读取权限，旧内核或策略拒绝时直接延期。 */
    const __u64 rights = LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR;
    const struct landlock_ruleset_attr rules = { .handled_access_fs = rights };
    int rules_fd = (int)syscall(SYS_landlock_create_ruleset, &rules, sizeof(rules), 0);
    if (rules_fd < 0) sandbox_failure();
    /* O_PATH 避免在限制前读取目录内容，规则按内核解析后的实际路径生效。 */
    int directory_fd = open(directory, O_PATH | O_CLOEXEC | O_DIRECTORY);
    if (directory_fd < 0) sandbox_failure();
    const struct landlock_path_beneath_attr rule = { .allowed_access = rights, .parent_fd = directory_fd };
    if (syscall(SYS_landlock_add_rule, rules_fd, LANDLOCK_RULE_PATH_BENEATH, &rule, 0) != 0
            || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0
            || syscall(SYS_landlock_restrict_self, rules_fd, 0) != 0) sandbox_failure();
    (void)close(directory_fd);
    (void)close(rules_fd);
}
